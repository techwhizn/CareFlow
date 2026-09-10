#!/usr/bin/env python3
"""Export and replay authenticated post-backup MySQL logs in isolated recovery."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess

from cold_backup import docker_json, inspect_stopped
from recovery_binlogs import probe, suffix
from recovery_crypto import (
    cipher_command,
    encrypt_command,
    file_digest,
    signature,
    verify_bundle,
)


def export(
    base_directory: Path,
    key: Path,
    project: str,
    output: Path,
    helper: str,
    tool_image: str,
):
    base = verify_bundle(base_directory, key)
    if base.get("kind") != "COLD_COMPOSE" or base.get("source_project") != project:
        raise ValueError("Increment must use the verified backup's original project")
    if not output.is_absolute() or output.exists() or output.is_symlink():
        raise ValueError("Increment output must be a new absolute directory")
    containers, volumes = inspect_stopped(project)
    image = docker_json("image", "inspect", helper)[0]["Id"]
    latest = probe(volumes["mysql-data"], image)
    files = suffix(base["mysql_checkpoint"], latest)
    if not files:
        raise ValueError("No post-backup binary log files to export")
    mysql = next(
        c
        for c in containers
        if c["Config"]["Labels"]["com.docker.compose.service"] == "mysql"
    )
    if mysql["Image"] != base["images"]["mysql"]:
        raise ValueError(
            "Rehearse database engine upgrades separately from log recovery"
        )
    tool = docker_json("image", "inspect", tool_image)[0]["Id"]
    version = subprocess.check_output(
        [
            "docker",
            "run",
            "--rm",
            "--pull",
            "never",
            "--network",
            "none",
            "--read-only",
            "--entrypoint",
            "mysqlbinlog",
            tool,
            "--version",
        ],
        text=True,
    ).strip()
    if "8.4.6" not in version:
        raise ValueError(
            "This recovery tool currently validates mysqlbinlog 8.4.6 only"
        )
    output.mkdir(mode=0o700)
    entry = encrypt_command(
        [
            "docker",
            "run",
            "--rm",
            "--pull",
            "never",
            "--network",
            "none",
            "--read-only",
            "--user",
            "0:0",
            "--mount",
            f"type=volume,src={volumes['mysql-data']},dst=/snapshot,readonly",
            "--entrypoint",
            "mysqlbinlog",
            tool,
            "--verify-binlog-checksum",
            "--skip-force-if-open",
            "--disable-log-bin",
            *["/snapshot/" + name for name in files],
        ],
        output / "mysql-replay.enc",
        key,
    )
    inspect_stopped(project)
    if probe(volumes["mysql-data"], image) != latest:
        raise ValueError("Source changed during export; increment is not complete")
    metadata = {
        "format": 1,
        "kind": "MYSQL_REPLAY",
        "status": "COMPLETE",
        "created_at": datetime.now(timezone.utc).isoformat(),
        "base_manifest_sha256": file_digest(base_directory / "manifest.json"),
        "latest_checkpoint": latest,
        "source_project": project,
        "mysqlbinlog_image": tool,
        "mysqlbinlog_version": version,
        "entries": [entry],
        "recovery_gate": "CLOSED_PENDING_CURRENT_STATE_AND_OBJECT_VERIFICATION",
    }
    (output / "manifest.json").write_text(
        json.dumps(
            {"metadata": metadata, "hmac_sha256": signature(metadata, key)}, indent=2
        )
        + "\n"
    )
    verify_bundle(output, key)
    print("Authenticated database increment exported; source remained stopped")


def apply(increment: Path, key: Path, configuration: Path, container: str):
    metadata = verify_bundle(increment, key)
    if metadata.get("kind") != "MYSQL_REPLAY":
        raise ValueError("Expected an authenticated MySQL increment")
    if [entry["file"] for entry in metadata["entries"]] != ["mysql-replay.enc"]:
        raise ValueError("Unexpected database increment entries")
    receipt_path = configuration / "recovery-receipt.json"
    receipt = json.loads(receipt_path.read_text())
    if receipt.get("status") != "STORAGE_RESTORED_ACCESS_CLOSED" or receipt.get(
        "mysql_replay"
    ):
        raise ValueError("Target is not a fresh, closed storage restore")
    if receipt["base_manifest_sha256"] != metadata["base_manifest_sha256"]:
        raise ValueError("Increment does not belong to this restored base")
    target = docker_json("inspect", container)[0]
    if target["HostConfig"]["NetworkMode"] != "none" or target["HostConfig"].get(
        "PortBindings"
    ):
        raise ValueError("Replay target must have no network or published ports")
    if target["Image"] != receipt["source_images"]["mysql"]:
        raise ValueError("Unexpected target database image")
    mounts = [m for m in target["Mounts"] if m["Destination"] == "/var/lib/mysql"]
    if len(mounts) != 1 or mounts[0].get("Name") != receipt["volumes"]["mysql-data"]:
        raise ValueError("Target does not mount the restored database volume")
    # Other clients must not have access to the target volume during replay.
    running = subprocess.check_output(
        [
            "docker",
            "ps",
            "--no-trunc",
            "-q",
            "--filter",
            "volume=" + receipt["volumes"]["mysql-data"],
        ],
        text=True,
    ).split()
    if running != [target["Id"]]:
        raise ValueError("Another running container mounts the recovery database")
    receipt["mysql_replay"] = {
        "status": "STARTED",
        "started_at": datetime.now(timezone.utc).isoformat(),
    }
    receipt_path.write_text(json.dumps(receipt, indent=2) + "\n")
    try:
        with (increment / "mysql-replay.enc").open("rb") as encrypted:
            with subprocess.Popen(
                cipher_command(key, decrypt=True),
                stdin=encrypted,
                stdout=subprocess.PIPE,
            ) as decryptor:
                try:
                    result = subprocess.run(
                        [
                            "docker",
                            "exec",
                            "-i",
                            container,
                            "sh",
                            "-c",
                            'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -u root --binary-mode',
                        ],
                        stdin=decryptor.stdout,
                        stdout=subprocess.DEVNULL,
                        stderr=subprocess.PIPE,
                    )
                    decryptor.stdout.close()
                    status = decryptor.wait()
                except BaseException:
                    decryptor.kill()
                    decryptor.wait()
                    raise
                if result.returncode or status:
                    raise RuntimeError(
                        "Database replay failed; discard partial target and restore a new base"
                    )
        receipt["mysql_replay"].update(
            status="APPLIED_ACCESS_CLOSED",
            latest_checkpoint=metadata["latest_checkpoint"],
        )
    except BaseException:
        receipt["mysql_replay"]["status"] = "FAILED_ACCESS_CLOSED"
        raise
    finally:
        receipt["mysql_replay"]["finished_at"] = datetime.now(timezone.utc).isoformat()
        receipt_path.write_text(json.dumps(receipt, indent=2) + "\n")
    print("Database increment applied in one session; business access remains CLOSED")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["export", "apply"])
    parser.add_argument("--key-file", type=Path, required=True)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--base-directory", type=Path)
    parser.add_argument("--project")
    parser.add_argument("--helper-image", default="careflow-worker:v1-validation")
    parser.add_argument("--mysqlbinlog-image", default="careflow-mysql-recovery:8.4.6")
    parser.add_argument("--configuration-output", type=Path)
    parser.add_argument("--mysql-container")
    args = parser.parse_args()
    os.umask(0o077)
    if args.action == "export":
        if not args.base_directory or not args.project:
            parser.error("export requires --base-directory and --project")
        export(
            args.base_directory,
            args.key_file,
            args.project,
            args.directory,
            args.helper_image,
            args.mysqlbinlog_image,
        )
    else:
        if not args.configuration_output or not args.mysql_container:
            parser.error("apply requires --configuration-output and --mysql-container")
        apply(
            args.directory,
            args.key_file,
            args.configuration_output,
            args.mysql_container,
        )


if __name__ == "__main__":
    main()
