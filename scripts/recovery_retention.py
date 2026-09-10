"""Retain authenticated recovery chains together; preserve each project's newest base."""

from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import fcntl
import json
import os
from pathlib import Path
import shutil

from recovery_crypto import file_digest, verify_bundle


def plan(root: Path, key: Path, days=30, *, now=None):
    if days < 30:
        raise ValueError("Retention must be at least 30 days")
    if not root.is_absolute() or root.is_symlink() or not root.is_dir():
        raise ValueError("Use an existing absolute, non-symlink backup root")
    if root.stat().st_mode & 0o077:
        raise ValueError("Backup root must be private to its owner")
    if key.resolve().is_relative_to(root.resolve()):
        raise ValueError("Key must be stored outside the retention root")
    cutoff = (now or datetime.now(timezone.utc)) - timedelta(days=days)
    bundles = {}
    for directory in sorted(root.iterdir()):
        if directory.is_symlink():
            raise ValueError("Symlinks are not allowed in the retention root")
        if not directory.is_dir():
            continue
        if not (directory / "manifest.json").exists():
            continue  # Unowned or incomplete data is never removed automatically.
        metadata = verify_bundle(directory, key)
        if metadata.get("kind") not in {"COLD_COMPOSE", "MYSQL_REPLAY"}:
            raise ValueError("Unknown recovery bundle kind")
        created = datetime.fromisoformat(metadata["created_at"])
        if created.tzinfo is None:
            raise ValueError("Backup timestamp must include timezone")
        entries = {entry["file"] for entry in metadata["entries"]} | {"manifest.json"}
        if {p.name for p in directory.iterdir()} != entries:
            raise ValueError(
                "Bundle contains unowned files; refusing automatic deletion"
            )
        digest = file_digest(directory / "manifest.json")
        if digest in bundles:
            raise ValueError("Duplicate manifest identity")
        bundles[digest] = dict(
            directory=directory.name, metadata=metadata, created=created
        )
    bases = {
        digest: row
        for digest, row in bundles.items()
        if row["metadata"]["kind"] == "COLD_COMPOSE"
    }
    chains = {digest: [digest] for digest in bases}
    newest = {}
    for digest, row in bases.items():
        project = row["metadata"]["source_project"]
        if (
            project not in newest
            or row["created"] > bundles[newest[project]]["created"]
        ):
            newest[project] = digest
    for digest, row in bundles.items():
        if row["metadata"]["kind"] == "MYSQL_REPLAY":
            parent = row["metadata"]["base_manifest_sha256"]
            if (
                parent not in bases
                or bases[parent]["metadata"]["source_project"]
                != row["metadata"]["source_project"]
            ):
                raise ValueError(
                    "Increment has no matching base in this retention root"
                )
            chains[parent].append(digest)
    removable = []
    for base, members in chains.items():
        if base in newest.values() or any(
            bundles[d]["created"] >= cutoff for d in members
        ):
            continue
        # Increment deletion precedes its base; a crash never strands a retained increment.
        for digest in sorted(members, key=lambda d: d == base):
            removable.append(
                {"directory": bundles[digest]["directory"], "manifest_sha256": digest}
            )
    return {
        "retention_days": days,
        "cutoff": cutoff.isoformat(),
        "remove": removable,
        "retained_bundles": len(bundles) - len(removable),
    }


def apply(root, key, proposal):
    if not shutil.rmtree.avoids_symlink_attacks:
        raise RuntimeError("Platform lacks symlink-safe removal")
    # The CLI holds the root lock; all writers must share the maintenance window.
    current = plan(
        root,
        key,
        proposal["retention_days"],
        now=datetime.fromisoformat(proposal["cutoff"])
        + timedelta(days=proposal["retention_days"]),
    )
    if current != proposal:
        raise ValueError("Retention plan changed; regenerate it")
    for entry in proposal["remove"]:
        directory = root / entry["directory"]
        verify_bundle(directory, key)
        if file_digest(directory / "manifest.json") != entry["manifest_sha256"]:
            raise ValueError("Bundle changed after planning")
        shutil.rmtree(directory)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--key-file", required=True, type=Path)
    parser.add_argument("--days", type=int, default=30)
    parser.add_argument(
        "--apply",
        action="store_true",
        help="Delete the authenticated expired chains shown by the plan",
    )
    args = parser.parse_args()
    os.umask(0o077)
    # Validate root before opening its lock; never follow a user-supplied lock symlink.
    proposal = plan(args.root, args.key_file, args.days)
    fd = os.open(
        args.root / ".retention.lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600
    )
    with os.fdopen(fd, "r+") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        proposal = plan(args.root, args.key_file, args.days)
        if args.apply:
            apply(args.root, args.key_file, proposal)
        print(json.dumps({"applied": args.apply, **proposal}, indent=2))


if __name__ == "__main__":
    main()
