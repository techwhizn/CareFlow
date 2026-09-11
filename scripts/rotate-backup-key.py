#!/usr/bin/env python3
"""Rotate the encryption key of an authenticated cold-backup bundle.

Entries are streamed from the old OpenSSL cipher directly into the new cipher;
no cleartext backup is written to disk. The original bundle is never modified.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

from recovery_crypto import cipher_command, file_digest, signature, verify_bundle


def rotate(source: Path, destination: Path, old_key: Path, new_key: Path) -> None:
    if not source.is_absolute() or not destination.is_absolute():
        raise ValueError("Source and destination must be absolute paths")
    if source.is_symlink() or not source.is_dir() or destination.exists():
        raise ValueError("Source must be a directory and destination must be new")
    if old_key.resolve() == new_key.resolve():
        raise ValueError("Old and new keys must be different")
    metadata = verify_bundle(source, old_key)
    signature({}, new_key)  # validate permissions and key format before output
    destination.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=destination.parent, prefix=".careflow-key-rotate-") as tmp:
        staged = Path(tmp)
        for entry in metadata["entries"]:
            name = entry["file"]
            source_path = source / name
            destination_path = staged / name
            with destination_path.open("wb") as output:
                decrypt = subprocess.Popen(
                    cipher_command(old_key, decrypt=True),
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    stdin=source_path.open("rb"),
                )
                encrypt = subprocess.Popen(
                    cipher_command(new_key),
                    stdin=decrypt.stdout,
                    stdout=output,
                    stderr=subprocess.PIPE,
                )
                decrypt.stdout.close()
                encrypt_error = encrypt.communicate()[1]
                decrypt_error = decrypt.communicate()[1]
                if decrypt.returncode or encrypt.returncode:
                    raise RuntimeError(
                        "Backup key rotation failed: "
                        + (decrypt_error + encrypt_error).decode(errors="replace")[:500]
                    )
            os.chmod(destination_path, 0o600)
            entry["sha256"] = file_digest(destination_path)
            entry["bytes"] = destination_path.stat().st_size
        metadata = dict(metadata)
        metadata["key_rotation"] = {"from": "external", "to": "external"}
        metadata["entries"] = [dict(item) for item in metadata["entries"]]
        envelope = {"metadata": metadata, "hmac_sha256": signature(metadata, new_key)}
        (staged / "manifest.json").write_text(json.dumps(envelope, indent=2) + "\n")
        os.chmod(staged / "manifest.json", 0o600)
        os.rename(staged, destination)
    verify_bundle(destination, new_key)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--old-key", required=True, type=Path)
    parser.add_argument("--new-key", required=True, type=Path)
    args = parser.parse_args()
    rotate(args.source, args.destination, args.old_key, args.new_key)
    print(f"Rotated backup written to {args.destination}")


if __name__ == "__main__":
    main()
