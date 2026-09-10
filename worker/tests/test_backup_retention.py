"""Retention never removes unknown backups or traverses symbolic links."""

import importlib.util
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

spec = importlib.util.spec_from_file_location(
    "managed_backups",
    Path(__file__).resolve().parents[2] / "scripts/managed_backups.py",
)
backups = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backups)


def test_retention_only_removes_expired_owned_directories(tmp_path):
    now = datetime.now(timezone.utc)
    root = backups.backup_root(str(tmp_path / "backups"))
    external = tmp_path / "protected"
    external.mkdir()
    (external / "content").write_text("synthetic")
    for name, age in [("old", 31), ("recent", 29), ("boundary", 30)]:
        directory = root / (backups.PREFIX + name)
        directory.mkdir()
        (directory / backups.MARKER).write_text(
            json.dumps(
                {
                    "format": 1,
                    "kind": "MYSQL_ONLY",
                    "directory": directory.name,
                    "status": "COMPLETE",
                    "created_at": (now - timedelta(days=age)).isoformat(),
                }
            )
        )
        (directory / "linked").symlink_to(external, target_is_directory=True)
    (root / (backups.PREFIX + "link")).symlink_to(external, target_is_directory=True)
    unknown = root / (backups.PREFIX + "unknown")
    unknown.mkdir()
    assert backups.retire(root, now=now) == 1
    assert backups.retire(root, now=now) == 0
    assert (external / "content").read_text() == "synthetic"
    assert unknown.is_dir()
    assert (root / (backups.PREFIX + "recent")).is_dir()
    assert (root / (backups.PREFIX + "boundary")).is_dir()


def test_retention_rejects_unsafe_root_and_invalid_period(tmp_path):
    with pytest.raises(ValueError):
        backups.backup_root("relative")
    link = tmp_path / "link"
    link.symlink_to(tmp_path, target_is_directory=True)
    with pytest.raises(ValueError):
        backups.backup_root(str(link))
    with pytest.raises(ValueError):
        backups.retire(tmp_path, 0)
