"""Retention preserves dependent increments and never silently deletes unowned data."""

import importlib.util
import json
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2] / "scripts"
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location(
    "recovery_retention", ROOT / "recovery_retention.py"
)
retention = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(retention)
from recovery_crypto import file_digest, signature  # noqa: E402


def bundle(root, key, name, age, *, parent=None):
    directory = root / name
    directory.mkdir()
    content = directory / "snapshot.enc"
    content.write_bytes(b"synthetic authenticated ciphertext fixture")
    metadata = {
        "format": 1,
        "status": "COMPLETE",
        "source_project": "synthetic",
        "kind": "MYSQL_REPLAY" if parent else "COLD_COMPOSE",
        "created_at": (
            datetime(2026, 9, 11, tzinfo=timezone.utc) - timedelta(days=age)
        ).isoformat(),
        "entries": [
            {
                "file": content.name,
                "sha256": file_digest(content),
                "bytes": content.stat().st_size,
            }
        ],
    }
    if parent:
        metadata["base_manifest_sha256"] = parent
    marker = directory / "manifest.json"
    marker.write_text(
        json.dumps({"metadata": metadata, "hmac_sha256": signature(metadata, key)})
    )
    return file_digest(marker)


def test_retention_keeps_recent_dependencies_and_newest_base(tmp_path):
    root = tmp_path / "backups"
    root.mkdir(mode=0o700)
    key = tmp_path / "key"
    key.write_text("a1" * 32)
    key.chmod(0o600)
    old = bundle(root, key, "old-base", 90)
    bundle(root, key, "old-increment", 80, parent=old)
    active = bundle(root, key, "active-base", 60)
    bundle(root, key, "recent-increment", 2, parent=active)
    bundle(root, key, "newest-base", 40)
    (root / "unowned").mkdir()
    now = datetime(2026, 9, 11, tzinfo=timezone.utc)
    proposed = retention.plan(root, key, now=now)
    assert [row["directory"] for row in proposed["remove"]] == [
        "old-increment",
        "old-base",
    ]
    retention.apply(root, key, proposed)
    assert {p.name for p in root.iterdir()} == {
        "active-base",
        "recent-increment",
        "newest-base",
        "unowned",
    }


def test_retention_rejects_orphans_tampering_and_extra_files(tmp_path):
    root = tmp_path / "backups"
    root.mkdir(mode=0o700)
    key = tmp_path / "key"
    key.write_text("b2" * 32)
    key.chmod(0o600)
    base = bundle(root, key, "base", 60)
    bundle(root, key, "increment", 50, parent=base)
    now = datetime(2026, 9, 11, tzinfo=timezone.utc)
    (root / "base" / "do-not-delete.txt").write_text("unowned")
    with pytest.raises(ValueError, match="unowned"):
        retention.plan(root, key, now=now)
    (root / "base" / "do-not-delete.txt").unlink()
    (root / "base" / "snapshot.enc").write_bytes(b"tampered")
    with pytest.raises(ValueError, match="authentication"):
        retention.plan(root, key, now=now)
    (root / "base").rename(tmp_path / "outside")
    with pytest.raises(ValueError, match="matching base"):
        retention.plan(root, key, now=now)
    with pytest.raises(ValueError, match="30 days"):
        retention.plan(root, key, 1, now=now)
