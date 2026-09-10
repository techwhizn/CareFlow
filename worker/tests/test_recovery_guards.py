import importlib.util
import json
from pathlib import Path

import pytest


def load(name, monkeypatch):
    scripts = Path(__file__).resolve().parents[2] / "scripts"
    monkeypatch.syspath_prepend(str(scripts))
    spec = importlib.util.spec_from_file_location(
        "test_" + name, scripts / (name + ".py")
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_backup_rejects_running_service_before_reading_volumes(monkeypatch):
    backup = load("cold_backup", monkeypatch)
    containers = [
        {
            "Config": {"Labels": {"com.docker.compose.service": name}},
            "State": {"Running": name == "backend"},
            "Mounts": [],
        }
        for name in backup.SERVICES
    ]
    monkeypatch.setattr(backup.subprocess, "check_output", lambda *a, **k: "container")
    monkeypatch.setattr(backup, "docker_json", lambda *a: containers)
    with pytest.raises(ValueError, match="Stop the entire deployment"):
        backup.inspect_stopped("synthetic-project")


def test_broker_restore_preserves_node_name_and_refuses_unknown_identity(
    monkeypatch, tmp_path
):
    broker = load("recovery_broker", monkeypatch)
    identity = broker.capture_identity(
        [
            {
                "Config": {
                    "Hostname": "original-node",
                    "Env": [],
                    "Labels": {"com.docker.compose.service": "rabbitmq"},
                }
            }
        ]
    )
    override = broker.compose_override(identity)["services"]["rabbitmq"]
    assert override["hostname"] == "original-node"
    assert override["environment"]["RABBITMQ_NODENAME"] == "rabbit@original-node"
    restore = load("cold-restore", monkeypatch)
    monkeypatch.setattr(restore, "verify_bundle", lambda *a: {"kind": "COLD_COMPOSE"})
    monkeypatch.setattr(
        restore.subprocess,
        "check_output",
        lambda *a, **k: pytest.fail("Must reject before Docker mutation"),
    )
    with pytest.raises(ValueError, match="node identity"):
        restore.restore(
            tmp_path, tmp_path / "key", "new-project", tmp_path / "new", "helper"
        )


@pytest.mark.parametrize(
    "identity",
    [
        {"hostname": "node", "nodename": "rabbit@other", "use_longname": False},
        {"hostname": "node\ninvalid", "nodename": "rabbit@node", "use_longname": False},
        {"hostname": "node", "nodename": "rabbit@node", "use_longname": "false"},
    ],
)
def test_broker_identity_rejects_mismatched_or_ambiguous_host_settings(
    monkeypatch, identity
):
    broker = load("recovery_broker", monkeypatch)
    with pytest.raises(ValueError):
        broker.compose_override(identity)


def test_replay_rejects_wrong_base_and_network_before_any_sql(tmp_path, monkeypatch):
    replay = load("mysql-recovery-log", monkeypatch)
    metadata = {
        "kind": "MYSQL_REPLAY",
        "base_manifest_sha256": "new-base",
        "entries": [{"file": "mysql-replay.enc"}],
    }
    monkeypatch.setattr(replay, "verify_bundle", lambda *a: metadata)
    receipt = {
        "status": "STORAGE_RESTORED_ACCESS_CLOSED",
        "base_manifest_sha256": "old-base",
    }
    path = tmp_path / "recovery-receipt.json"
    path.write_text(json.dumps(receipt))
    with pytest.raises(ValueError, match="does not belong"):
        replay.apply(tmp_path, tmp_path / "key", tmp_path, "target")
    receipt["base_manifest_sha256"] = "new-base"
    path.write_text(json.dumps(receipt))
    monkeypatch.setattr(
        replay, "docker_json", lambda *a: [{"HostConfig": {"NetworkMode": "bridge"}}]
    )
    with pytest.raises(ValueError, match="no network"):
        replay.apply(tmp_path, tmp_path / "key", tmp_path, "target")
    assert "mysql_replay" not in json.loads(path.read_text())


def test_replay_refuses_to_repeat_started_or_failed_increment(tmp_path, monkeypatch):
    replay = load("mysql-recovery-log", monkeypatch)
    monkeypatch.setattr(
        replay,
        "verify_bundle",
        lambda *a: {"kind": "MYSQL_REPLAY", "entries": [{"file": "mysql-replay.enc"}]},
    )
    for status in ("STARTED", "FAILED_ACCESS_CLOSED", "APPLIED_ACCESS_CLOSED"):
        (tmp_path / "recovery-receipt.json").write_text(
            json.dumps(
                {
                    "status": "STORAGE_RESTORED_ACCESS_CLOSED",
                    "mysql_replay": {"status": status},
                }
            )
        )
        with pytest.raises(ValueError, match="not a fresh"):
            replay.apply(tmp_path, tmp_path / "key", tmp_path, "target")
