"""Pinned official model snapshots; model data stays outside version control."""

import hashlib
import os
from pathlib import Path

MODELS = {
    "embedding": ("BAAI/bge-small-zh-v1.5", "7999e1d3359715c523056ef9478215996d62a620"),
    "rerank": ("BAAI/bge-reranker-base", "2cfc18c9415c912f9d8155881c133215df768a70"),
}


def model_path(kind):
    configured = os.environ.get("LOCAL_MODEL_DIRECTORY")
    root = (
        Path(configured)
        if configured
        else Path(__file__).resolve().parents[2] / ".local/models"
    )
    name, revision = MODELS[kind]
    return root / (name.split("/")[1] + "-" + revision)


WEIGHT_HASHES = {
    "embedding": "354763b9b1357bc9c44f62c6be2276321081ed2567773608c0d0785b61d5a026",
    "rerank": "ced967c45fd1902eb92716c9ceeca7c95a936770ea9db611f5a841b926e33fbd",
}


def verify_snapshot(kind):
    digest = hashlib.sha256()
    with (model_path(kind) / "model.safetensors").open("rb") as weights:
        while data := weights.read(1024 * 1024):
            digest.update(data)
    if digest.hexdigest() != WEIGHT_HASHES[kind]:
        raise ValueError("Model weight checksum mismatch")
