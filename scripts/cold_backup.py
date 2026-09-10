#!/usr/bin/env python3
"""Create an encrypted snapshot of a stopped, local Docker Compose deployment."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import secrets
import subprocess

from recovery_crypto import encrypt_command, signature, verify_bundle
from recovery_binlogs import probe

SERVICES = {
    "mysql",
    "rabbitmq",
    "minio",
    "etcd",
    "milvus",
    "backend",
    "worker-api",
    "worker-consumer",
    "web",
}
VOLUMES = {"mysql-data", "rabbit-data", "s3-data", "etcd-data", "milvus-data"}


def docker_json(*args):
    return json.loads(subprocess.check_output(["docker", *args], text=True))


def inspect_stopped(project: str) -> tuple[list[dict], dict[str, str]]:
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]+", project):
        raise ValueError("Invalid Compose project name")
    ids = subprocess.check_output(
        [
            "docker",
            "ps",
            "-aq",
            "--filter",
            "label=com.docker.compose.project=" + project,
        ],
        text=True,
    ).split()
    if not ids:
        raise ValueError("No deployment containers found")
    containers = docker_json("inspect", *ids)
    services = {
        c["Config"]["Labels"].get("com.docker.compose.service") for c in containers
    }
    if services != SERVICES or len(containers) != len(SERVICES):
        raise ValueError("Expected exactly the nine default deployment services")
    if any(c["State"]["Running"] or c["State"].get("Restarting") for c in containers):
        raise ValueError("Stop the entire deployment before capturing its volumes")
    mounts = {
        m["Name"] for c in containers for m in c["Mounts"] if m["Type"] == "volume"
    }
    volumes = {}
    for volume in docker_json("volume", "inspect", *sorted(mounts)):
        labels = volume.get("Labels") or {}
        logical = labels.get("com.docker.compose.volume")
        if (
            labels.get("com.docker.compose.project") != project
            or volume["Driver"] != "local"
        ):
            raise ValueError("Only project-owned local volumes are supported")
        if logical in volumes:
            raise ValueError("Duplicate volume identity")
        volumes[logical] = volume["Name"]
    if set(volumes) != VOLUMES:
        raise ValueError("Unexpected deployment volume layout")
    # A container outside this project must not still write one of these volumes.
    for volume in volumes.values():
        running = subprocess.check_output(
            ["docker", "ps", "-q", "--filter", "volume=" + volume],
            text=True,
        ).strip()
        if running:
            raise ValueError("A running container still mounts a backup volume")
    return containers, volumes


def capture(
    project: str, destination: Path, key: Path, configurations: list[Path], helper: str
):
    if (
        not destination.is_absolute()
        or destination.exists()
        or destination.is_symlink()
    ):
        raise ValueError("Destination must be a new absolute directory")
    if not configurations or any(
        not p.is_file() or p.is_symlink() for p in configurations
    ):
        raise ValueError(
            "Provide regular configuration files, including the private environment"
        )
    if key.resolve().is_relative_to(destination.resolve()):
        raise ValueError("Encryption key must be stored outside the backup")
    # Validate the key before starting any external producer.
    signature({}, key)
    containers, volumes = inspect_stopped(project)
    image = docker_json("image", "inspect", helper)[0]["Id"]
    checkpoint = probe(volumes["mysql-data"], image)
    destination.mkdir(mode=0o700)
    metadata = {
        "format": 1,
        "kind": "COLD_COMPOSE",
        "status": "STARTED",
        "created_at": datetime.now(timezone.utc).isoformat(),
        "source_project": project,
        "images": {
            c["Config"]["Labels"]["com.docker.compose.service"]: c["Image"]
            for c in containers
        },
        "entries": [],
        "mysql_checkpoint": checkpoint,
        "recovery_gate": "CLOSED_REQUIRES_CURRENT_SECURITY_STATE",
        "limitations": [
            "Requires exclusive maintenance window; do not start services during capture",
            "Preserves MySQL/MinIO/Milvus binary formats; use compatible image versions",
            "External model weights and providers must remain available by fixed identity",
        ],
    }
    try:
        for logical, volume in sorted(volumes.items()):
            entry = encrypt_command(
                [
                    "docker",
                    "run",
                    "--rm",
                    "--pull",
                    "never",
                    "--network",
                    "none",
                    "--user",
                    "0:0",
                    "--read-only",
                    "--mount",
                    f"type=volume,src={volume},dst=/snapshot,readonly",
                    image,
                    "tar",
                    "-C",
                    "/snapshot",
                    "-cf",
                    "-",
                    ".",
                ],
                destination / (logical + ".enc"),
                key,
            )
            entry["volume"] = logical
            metadata["entries"].append(entry)
        # The configuration tar uses basename entries; reject duplicate names.
        if len({p.name for p in configurations}) != len(configurations):
            raise ValueError("Configuration basenames must be unique")
        command = ["tar", "-cf", "-"]
        for path in configurations:
            command.extend(["-C", str(path.resolve().parent), path.name])
        metadata["entries"].append(
            encrypt_command(command, destination / "configuration.enc", key)
        )
        inspect_stopped(project)
        if probe(volumes["mysql-data"], image) != checkpoint:
            raise ValueError("Database log boundary changed during capture")
        metadata["status"] = "COMPLETE"
    finally:
        metadata["finished_at"] = datetime.now(timezone.utc).isoformat()
        envelope = {"metadata": metadata, "hmac_sha256": signature(metadata, key)}
        (destination / "manifest.json").write_text(
            json.dumps(envelope, indent=2) + "\n"
        )
    verify_bundle(destination, key)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["keygen", "capture", "verify"])
    parser.add_argument("--key-file", type=Path, required=True)
    parser.add_argument("--directory", type=Path)
    parser.add_argument("--project")
    parser.add_argument("--configuration", type=Path, action="append", default=[])
    parser.add_argument("--helper-image", default="careflow-worker:v1-validation")
    args = parser.parse_args()
    os.umask(0o077)
    if args.action == "keygen":
        with args.key_file.open("x") as stream:
            stream.write(secrets.token_hex(32) + "\n")
        print("Key created; store separately in controlled key storage")
    elif args.action == "capture":
        if not args.project or args.directory is None:
            parser.error("capture requires --project and --directory")
        capture(
            args.project,
            args.directory,
            args.key_file,
            args.configuration,
            args.helper_image,
        )
        print("Encrypted cold snapshot verified; recovery access remains CLOSED")
    else:
        if args.directory is None:
            parser.error("verify requires --directory")
        verify_bundle(args.directory, args.key_file)
        print("Backup authentication and ciphertext integrity verified")


if __name__ == "__main__":
    main()
