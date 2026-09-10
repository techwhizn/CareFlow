"""Authenticated local backup encryption using OpenSSL and a separate HMAC key.

The 256-bit random key file must be stored separately from the backup. Ciphertext
is authenticated before any plaintext is released; this is encrypt-then-MAC.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import subprocess


def read_key(path: Path) -> bytes:
    if path.is_symlink() or not path.is_file():
        raise ValueError("Key must be a regular file")
    if path.stat().st_mode & 0o077:
        raise ValueError("Key file must not be accessible to group or other users")
    raw = path.read_bytes().strip()
    if not re.fullmatch(rb"[0-9a-f]{64}", raw):
        raise ValueError("Expected a random 256-bit key encoded as lowercase hex")
    return bytes.fromhex(raw.decode("ascii"))


def authentication_key(path: Path) -> bytes:
    return hmac.digest(read_key(path), b"CareFlow backup manifest v1", "sha256")


def file_digest(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def signature(metadata: dict, key: Path) -> str:
    content = json.dumps(metadata, sort_keys=True, separators=(",", ":")).encode()
    return hmac.new(authentication_key(key), content, hashlib.sha256).hexdigest()


def cipher_command(key: Path, *, decrypt=False) -> list[str]:
    read_key(key)
    return [
        "openssl",
        "enc",
        "-aes-256-cbc",
        "-pbkdf2",
        "-iter",
        "600000",
        "-pass",
        "file:" + str(key.resolve()),
    ] + (["-d"] if decrypt else [])


def encrypt_command(command: list[str], destination: Path, key: Path) -> dict:
    """Pipe an authorized producer directly into encryption, never a cleartext file."""
    cipher = cipher_command(key)
    if destination.exists() or destination.is_symlink():
        raise ValueError("Refusing to overwrite a backup entry")
    descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as output:
        with subprocess.Popen(command, stdout=subprocess.PIPE) as producer:
            try:
                result = subprocess.run(
                    cipher,
                    stdin=producer.stdout,
                    stdout=output,
                    stderr=subprocess.PIPE,
                    check=False,
                )
                producer.stdout.close()
                producer_status = producer.wait()
            except BaseException:
                producer.kill()
                producer.wait()
                raise
            if result.returncode or producer_status:
                raise RuntimeError("Backup producer or encryption failed")
    return {
        "file": destination.name,
        "sha256": file_digest(destination),
        "bytes": destination.stat().st_size,
    }


def verify_bundle(directory: Path, key: Path) -> dict:
    marker = directory / "manifest.json"
    if marker.is_symlink() or marker.stat().st_size > 1024 * 1024:
        raise ValueError("Invalid backup manifest")
    envelope = json.loads(marker.read_text())
    metadata = envelope["metadata"]
    if not hmac.compare_digest(signature(metadata, key), envelope["hmac_sha256"]):
        raise ValueError("Backup authentication failed")
    if metadata.get("format") != 1 or metadata.get("status") != "COMPLETE":
        raise ValueError("Incomplete or unsupported backup")
    seen = set()
    for entry in metadata["entries"]:
        name = entry["file"]
        if not re.fullmatch(r"[a-z0-9_-]+\.enc", name) or name in seen:
            raise ValueError("Invalid or duplicate backup entry")
        seen.add(name)
        path = directory / name
        if path.is_symlink() or not path.is_file():
            raise ValueError("Invalid backup entry")
        if (
            path.stat().st_size != entry["bytes"]
            or file_digest(path) != entry["sha256"]
        ):
            raise ValueError("Backup content authentication failed")
    if not seen:
        raise ValueError("Empty backup")
    return metadata
