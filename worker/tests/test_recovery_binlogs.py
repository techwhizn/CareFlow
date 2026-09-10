import importlib.util
from pathlib import Path

import pytest

SPEC = importlib.util.spec_from_file_location(
    "recovery_binlogs",
    Path(__file__).resolve().parents[2] / "scripts/recovery_binlogs.py",
)
binlogs = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(binlogs)


def test_closed_boundary_requires_same_source_and_unchanged_file():
    boundary = {"file": "binlog.000002", "bytes": 100, "sha256": "original"}
    newer = {"file": "binlog.000003", "bytes": 500, "sha256": "new"}
    base = {"server_identity_sha256": "source", "files": [boundary]}
    latest = {"server_identity_sha256": "source", "files": [boundary, newer]}
    assert binlogs.suffix(base, latest) == ["binlog.000003"]
    assert binlogs.suffix(base, base) == []
    with pytest.raises(ValueError, match="different MySQL source"):
        binlogs.suffix(base, {**latest, "server_identity_sha256": "other"})
    with pytest.raises(ValueError, match="purged, replaced or modified"):
        binlogs.suffix(base, {**latest, "files": [newer]})
    with pytest.raises(ValueError, match="purged, replaced or modified"):
        binlogs.suffix(
            base, {**latest, "files": [{**boundary, "sha256": "changed"}, newer]}
        )
