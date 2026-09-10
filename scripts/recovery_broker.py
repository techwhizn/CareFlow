"""Preserve RabbitMQ node identity when restoring its on-disk metadata."""

import re


def validate_identity(identity):
    if not isinstance(identity, dict) or set(identity) != {
        "hostname",
        "nodename",
        "use_longname",
    }:
        raise ValueError(
            "Backup lacks a verified RabbitMQ node identity; recapture before recovery"
        )
    hostname = identity["hostname"]
    nodename = identity["nodename"]
    if not isinstance(hostname, str) or not re.fullmatch(
        r"[A-Za-z0-9][A-Za-z0-9.-]{0,252}", hostname
    ):
        raise ValueError("Unsupported RabbitMQ hostname")
    if not isinstance(nodename, str) or not re.fullmatch(
        r"[A-Za-z0-9_-]+@" + re.escape(hostname), nodename
    ):
        raise ValueError(
            "RabbitMQ node host must match the captured container hostname"
        )
    if not isinstance(identity["use_longname"], bool):
        raise ValueError("Invalid RabbitMQ long-name setting")
    return identity


def capture_identity(containers):
    broker = next(
        c
        for c in containers
        if c["Config"]["Labels"]["com.docker.compose.service"] == "rabbitmq"
    )
    environment = dict(
        value.split("=", 1) for value in broker["Config"].get("Env", []) if "=" in value
    )
    hostname = broker["Config"]["Hostname"]
    for key in [
        "RABBITMQ_MNESIA_BASE",
        "RABBITMQ_MNESIA_DIR",
        "RABBITMQ_CONF_ENV_FILE",
    ]:
        if key in environment:
            raise ValueError(
                "Custom RabbitMQ data/environment paths require a separate recovery plan"
            )
    longname = environment.get("RABBITMQ_USE_LONGNAME", "false")
    if longname not in {"true", "false"}:
        raise ValueError("Unsupported RabbitMQ long-name setting")
    return validate_identity(
        {
            "hostname": hostname,
            "nodename": environment.get("RABBITMQ_NODENAME", "rabbit@" + hostname),
            "use_longname": longname == "true",
        }
    )


def compose_override(identity):
    identity = validate_identity(identity)
    return {
        "services": {
            "rabbitmq": {
                "hostname": identity["hostname"],
                "environment": {
                    "RABBITMQ_NODENAME": identity["nodename"],
                    "RABBITMQ_USE_LONGNAME": str(identity["use_longname"]).lower(),
                },
            }
        }
    }
