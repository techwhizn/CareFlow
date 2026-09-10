#!/usr/bin/env python3
"""Restore authenticated volumes to a new project; never start application services.

This prepares isolated recovery storage only. It deliberately does not open the
old authorization state or claim that current deletion/revocation replay passed.
"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile

from recovery_crypto import cipher_command, file_digest, verify_bundle
from recovery_broker import compose_override

VOLUMES = {"mysql-data", "rabbit-data", "s3-data", "etcd-data", "milvus-data"}


def restore(directory: Path, key: Path, project: str, configuration: Path, helper: str):
    metadata = verify_bundle(directory, key)
    if metadata.get("kind") != "COLD_COMPOSE":
        raise ValueError("Expected a cold Compose snapshot")
    broker_override = compose_override(metadata.get("rabbitmq_identity"))
    if (
        not re.fullmatch(r"[a-z0-9][a-z0-9_-]+", project)
        or project == metadata["source_project"]
    ):
        raise ValueError("Recovery requires a distinct, new Compose project")
    entries = {
        entry.get("volume"): entry
        for entry in metadata["entries"]
        if entry.get("volume")
    }
    if set(entries) != VOLUMES or len(metadata["entries"]) != 6:
        raise ValueError("Incomplete deployment snapshot")
    if (
        not configuration.is_absolute()
        or configuration.exists()
        or configuration.is_symlink()
    ):
        raise ValueError("Configuration output must be a new absolute directory")
    existing = subprocess.check_output(
        [
            "docker",
            "ps",
            "-aq",
            "--filter",
            "label=com.docker.compose.project=" + project,
        ],
        text=True,
    ).strip()
    volumes = {logical: project + "_" + logical for logical in VOLUMES}
    existing_volumes = set(
        subprocess.check_output(["docker", "volume", "ls", "-q"], text=True).split()
    )
    if existing or existing_volumes.intersection(volumes.values()):
        raise ValueError("Recovery target is not empty; refusing overwrite")
    image = json.loads(
        subprocess.check_output(["docker", "image", "inspect", helper], text=True)
    )[0]["Id"]
    configuration.mkdir(mode=0o700)
    receipt = {
        "format": 1,
        "started_at": datetime.now(timezone.utc).isoformat(),
        "project": project,
        "status": "STARTED",
        "volumes": volumes,
        "recovery_gate": "CLOSED_REQUIRES_CURRENT_SECURITY_STATE",
        "source_images": metadata["images"],
        "rabbitmq_identity": metadata["rabbitmq_identity"],
        "base_manifest_sha256": file_digest(directory / "manifest.json"),
    }
    try:
        for logical, name in sorted(volumes.items()):
            subprocess.run(
                [
                    "docker",
                    "volume",
                    "create",
                    "--label",
                    "com.docker.compose.project=" + project,
                    "--label",
                    "com.docker.compose.volume=" + logical,
                    name,
                ],
                check=True,
                stdout=subprocess.DEVNULL,
            )
            with (directory / entries[logical]["file"]).open("rb") as encrypted:
                with subprocess.Popen(
                    cipher_command(key, decrypt=True),
                    stdin=encrypted,
                    stdout=subprocess.PIPE,
                ) as decryptor:
                    try:
                        result = subprocess.run(
                            [
                                "docker",
                                "run",
                                "--rm",
                                "-i",
                                "--pull",
                                "never",
                                "--network",
                                "none",
                                "--read-only",
                                "--user",
                                "0:0",
                                "--mount",
                                f"type=volume,src={name},dst=/restore",
                                image,
                                "tar",
                                "-C",
                                "/restore",
                                "-xf",
                                "-",
                            ],
                            stdin=decryptor.stdout,
                            check=False,
                        )
                        decryptor.stdout.close()
                        status = decryptor.wait()
                    except BaseException:
                        decryptor.kill()
                        decryptor.wait()
                        raise
                    if result.returncode or status:
                        raise RuntimeError("Volume decryption or extraction failed")
        configuration_entry = next(
            e for e in metadata["entries"] if e["file"] == "configuration.enc"
        )
        if configuration_entry["bytes"] > 16 * 1024 * 1024:
            raise ValueError("Configuration archive exceeds 16 MiB")
        with (directory / "configuration.enc").open("rb") as encrypted:
            plaintext = subprocess.check_output(
                cipher_command(key, decrypt=True), stdin=encrypted
            )
        with tarfile.open(fileobj=io.BytesIO(plaintext), mode="r:") as archive:
            seen = set()
            for member in archive:
                if (
                    not member.isfile()
                    or Path(member.name).name != member.name
                    or member.name in seen
                ):
                    raise ValueError(
                        "Configuration archive must contain unique regular basenames"
                    )
                if (
                    member.name
                    in {
                        ".",
                        "..",
                        "recovery-receipt.json",
                        "recovery-broker.compose.json",
                    }
                    or member.size > 4 * 1024 * 1024
                ):
                    raise ValueError("Invalid configuration entry")
                seen.add(member.name)
                with (configuration / member.name).open("xb") as output:
                    output.write(archive.extractfile(member).read())
        (configuration / "recovery-broker.compose.json").write_text(
            json.dumps(broker_override, indent=2) + "\n"
        )
        receipt["status"] = "STORAGE_RESTORED_ACCESS_CLOSED"
    finally:
        receipt["finished_at"] = datetime.now(timezone.utc).isoformat()
        (configuration / "recovery-receipt.json").write_text(
            json.dumps(receipt, indent=2) + "\n"
        )
    print(
        "Storage restored; include recovery-broker.compose.json in Compose. No service started; access remains CLOSED"
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--key-file", type=Path, required=True)
    parser.add_argument("--project", required=True)
    parser.add_argument("--configuration-output", type=Path, required=True)
    parser.add_argument("--helper-image", default="careflow-worker:v1-validation")
    args = parser.parse_args()
    os.umask(0o077)
    restore(
        args.directory,
        args.key_file,
        args.project,
        args.configuration_output,
        args.helper_image,
    )


if __name__ == "__main__":
    main()
