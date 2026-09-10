"""Exercise actual encryption and tamper rejection with synthetic backup content."""

import importlib.util
import json
import subprocess
import sys
from pathlib import Path

import pytest

SPEC = importlib.util.spec_from_file_location(
    "recovery_crypto",
    Path(__file__).resolve().parents[2] / "scripts/recovery_crypto.py",
)
crypto = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(crypto)


def test_encrypt_authenticate_and_reject_tampering(tmp_path):
    key = tmp_path / "key"
    key.write_text("a1" * 32 + "\n")
    key.chmod(0o600)
    directory = tmp_path / "backup"
    directory.mkdir()
    entry = crypto.encrypt_command(
        [sys.executable, "-c", "print('synthetic backup data')"],
        directory / "configuration.enc",
        key,
    )
    metadata = {"format": 1, "status": "COMPLETE", "entries": [entry]}
    manifest = directory / "manifest.json"
    manifest.write_text(
        json.dumps(
            {"metadata": metadata, "hmac_sha256": crypto.signature(metadata, key)}
        )
    )
    assert crypto.verify_bundle(directory, key) == metadata
    with (directory / entry["file"]).open("rb") as stream:
        cleartext = subprocess.check_output(
            crypto.cipher_command(key, decrypt=True), stdin=stream
        )
    assert cleartext == b"synthetic backup data\n"
    damaged = bytearray((directory / entry["file"]).read_bytes())
    damaged[-1] ^= 1
    (directory / entry["file"]).write_bytes(damaged)
    with pytest.raises(ValueError, match="content authentication"):
        crypto.verify_bundle(directory, key)
    metadata["entries"][0]["sha256"] = crypto.file_digest(directory / entry["file"])
    envelope = json.loads(manifest.read_text())
    envelope["metadata"] = metadata
    manifest.write_text(json.dumps(envelope))
    with pytest.raises(ValueError, match="authentication failed"):
        crypto.verify_bundle(directory, key)


def test_key_permissions_and_failed_producer(tmp_path):
    key = tmp_path / "key"
    key.write_text("b2" * 32)
    key.chmod(0o644)
    with pytest.raises(ValueError, match="accessible"):
        crypto.read_key(key)
    key.chmod(0o600)
    with pytest.raises(RuntimeError, match="producer or encryption"):
        crypto.encrypt_command(
            [sys.executable, "-c", "raise SystemExit(3)"], tmp_path / "failed.enc", key
        )
