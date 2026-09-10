"""Read stopped MySQL binary-log positions without exposing logged row contents."""

import json
import subprocess

PROBE = """
import hashlib, json, re
from pathlib import Path
root = Path('/snapshot')
names = [Path(line).name for line in (root/'binlog.index').read_text().splitlines()]
if not names or any(not re.fullmatch(r'binlog\\.[0-9]{6}', n) for n in names):
    raise ValueError('Unsupported binary log index')
numbers = [int(n.split('.')[1]) for n in names]
if numbers != list(range(numbers[0], numbers[-1]+1)):
    raise ValueError('Non-contiguous binary log index')
rows = []
for name in names:
    p = root/name
    if p.is_symlink() or not p.is_file():
        raise ValueError('Invalid binary log file')
    digest = hashlib.sha256()
    with p.open('rb') as f:
        for block in iter(lambda: f.read(1048576), b''):
            digest.update(block)
    rows.append({'file':name,'bytes':p.stat().st_size,'sha256':digest.hexdigest()})
print(json.dumps({'files':rows,'server_identity_sha256':hashlib.sha256((root/'auto.cnf').read_bytes()).hexdigest()}))
"""


def probe(volume: str, helper_image: str) -> dict:
    return json.loads(
        subprocess.check_output(
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
                f"type=volume,src={volume},dst=/snapshot,readonly",
                helper_image,
                "python",
                "-c",
                PROBE,
            ],
            text=True,
        )
    )


def suffix(base: dict, latest: dict) -> list[str]:
    if base["server_identity_sha256"] != latest["server_identity_sha256"]:
        raise ValueError("Binary logs belong to a different MySQL source")
    checkpoint = base["files"][-1]
    candidates = latest["files"]
    matches = [
        i for i, row in enumerate(candidates) if row["file"] == checkpoint["file"]
    ]
    if len(matches) != 1 or candidates[matches[0]] != checkpoint:
        raise ValueError("Backup boundary was purged, replaced or modified")
    # A stopped server closes its log. We require that exact file to remain;
    # newly opened files are replayed from their first event, in one session.
    return [row["file"] for row in candidates[matches[0] + 1 :]]
