#!/usr/bin/env python3
"""Local MySQL dump helper with explicit ownership and bounded retention.

This is not a consistent cross-store backup. See docs/operations.md.
"""

from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import shutil
import subprocess
import uuid

MARKER = ".careflow-backup.json"
PREFIX = "careflow-backup-"


def backup_root(path: str) -> Path:
    root = Path(path)
    if not root.is_absolute() or root.is_symlink():
        raise ValueError("Backup root must be an absolute, non-symlink directory")
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    if not root.is_dir() or root.resolve() == Path("/"):
        raise ValueError("Invalid backup root")
    return root.resolve()


def retire(root: Path, days: int = 30, *, now: datetime | None = None) -> int:
    if days < 1:
        raise ValueError("Retention must be at least one day")
    cutoff = (now or datetime.now(timezone.utc)) - timedelta(days=days)
    removed = 0
    for directory in root.iterdir():
        if (
            not directory.name.startswith(PREFIX)
            or directory.is_symlink()
            or not directory.is_dir()
        ):
            continue
        marker = directory / MARKER
        if marker.is_symlink() or not marker.is_file():
            continue
        try:
            if marker.stat().st_size > 4096:
                continue
            metadata = json.loads(marker.read_text())
            if not isinstance(metadata, dict):
                continue
            created = datetime.fromisoformat(metadata["created_at"])
            if (
                metadata.get("format") != 1
                or metadata.get("kind") != "MYSQL_ONLY"
                or metadata.get("directory") != directory.name
                or metadata.get("status") not in {"STARTED", "COMPLETE", "FAILED"}
                or created.tzinfo is None
                or created >= cutoff
            ):
                continue
        except (OSError, ValueError, TypeError, KeyError):
            continue
        # fd-based rmtree avoids following symlinks swapped into the owned tree.
        if not shutil.rmtree.avoids_symlink_attacks:
            raise RuntimeError("Platform lacks symlink-safe directory removal")
        shutil.rmtree(directory)
        removed += 1
    return removed


def mysql_backup(root: Path) -> Path:
    created = datetime.now(timezone.utc)
    name = PREFIX + created.strftime("%Y%m%dT%H%M%SZ-") + uuid.uuid4().hex
    directory = root / name
    directory.mkdir(mode=0o700)
    metadata = {
        "format": 1,
        "kind": "MYSQL_ONLY",
        "directory": name,
        "created_at": created.isoformat(),
        "status": "STARTED",
    }

    def save():
        temporary = directory / (MARKER + ".tmp")
        temporary.write_text(json.dumps(metadata) + "\n")
        temporary.replace(directory / MARKER)

    save()
    try:
        partial = directory / "mysql.sql.partial"
        with partial.open("xb") as output:
            subprocess.run(
                [
                    "docker",
                    "compose",
                    "exec",
                    "-T",
                    "mysql",
                    "sh",
                    "-c",
                    'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysqldump -u root --single-transaction --routines --triggers careflow',
                ],
                cwd=Path(__file__).resolve().parents[1],
                stdout=output,
                check=True,
            )
        partial.replace(directory / "mysql.sql")
        metadata["status"] = "COMPLETE"
        save()
    except BaseException:
        metadata["status"] = "FAILED"
        save()
        raise
    return directory


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["mysql", "retire"])
    parser.add_argument("root")
    parser.add_argument("--retention-days", type=int, default=30)
    args = parser.parse_args()
    if args.retention_days < 1:
        parser.error("retention-days must be positive")
    os.umask(0o077)
    root = backup_root(args.root)
    try:
        if args.action == "mysql":
            directory = mysql_backup(root)
            print(
                f"MySQL-only snapshot: {directory}; cross-store recovery not verified."
            )
    finally:
        # Retention remains scheduled even when the producer fails, so an
        # interrupted run cannot silently disable lifecycle cleanup.
        print(f"Expired managed snapshots removed: {retire(root, args.retention_days)}")


if __name__ == "__main__":
    main()
