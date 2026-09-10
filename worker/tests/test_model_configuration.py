"""Concurrent configuration and internal contract tests use no real model requests."""

import json
import threading
from concurrent.futures import ThreadPoolExecutor

import pytest
from fastapi.testclient import TestClient

from careflow import api, models
from careflow.model_configuration import ModelConfiguration, use_configuration, value


def configuration(kind="EMBEDDING", model="snapshot-model", key="private-snapshot-key"):
    return ModelConfiguration(
        kind=kind,
        base_url="https://model.invalid/v1",
        model=model,
        revision="immutable-sha" if kind == "EMBEDDING" else "",
        dimensions=512 if kind == "EMBEDDING" else None,
        api_key=key,
    )


def test_snapshot_preserves_existing_identity_and_does_not_hash_credentials(
    monkeypatch,
):
    config = configuration()
    for key, val in {
        "EMBEDDING_BASE_URL": config.base_url,
        "EMBEDDING_MODEL": config.model,
        "EMBEDDING_REVISION": config.revision,
        "EMBEDDING_DIMENSIONS": str(config.dimensions),
    }.items():
        monkeypatch.setenv(key, val)
    assert (
        config.identity()
        == "8b183c56b3f2c2da0565efe5d126b1fe9252968bed3112717c75686eaeb3fe26"
    )
    assert config.identity() == models.identity()
    assert configuration(key="rotated").identity() == config.identity()
    assert "private-snapshot-key" not in repr(config)
    assert "private-snapshot-key" not in config.model_dump_json()


def test_concurrent_tenants_use_their_own_model_and_restore_after_failure(monkeypatch):
    monkeypatch.setenv("EMBEDDING_MODEL", "deployment-default")
    barrier = threading.Barrier(2)

    def execute(name):
        with pytest.raises(RuntimeError):
            with use_configuration(configuration(model=name, key=name + "-key")):
                barrier.wait(timeout=5)
                assert models.endpoint("EMBEDDING", "/embeddings")[1] == name
                assert value("EMBEDDING_API_KEY") == name + "-key"
                raise RuntimeError("synthetic failure")
        assert value("EMBEDDING_MODEL") == "deployment-default"

    with ThreadPoolExecutor(max_workers=2) as pool:
        list(pool.map(execute, ["tenant-a", "tenant-b"]))
    assert value("EMBEDDING_MODEL") == "deployment-default"


def test_nested_context_restores_outer_model(monkeypatch):
    monkeypatch.setenv("RERANK_MODEL", "deployment-rerank")
    with use_configuration(configuration(model="outer")):
        with use_configuration(configuration(model="inner")):
            assert value("EMBEDDING_MODEL") == "inner"
        assert value("EMBEDDING_MODEL") == "outer"
        assert value("RERANK_MODEL") == "deployment-rerank"


@pytest.mark.parametrize(
    "url",
    [
        "file:///tmp/key",
        "https://user:secret@model.invalid",
        "http://model.invalid/?token=private",
        "https://model.invalid/arbitrary",
    ],
)
def test_unbounded_endpoint_shapes_are_rejected(url):
    with pytest.raises(ValueError):
        ModelConfiguration.model_validate(
            {**configuration().model_dump(), "base_url": url}
        )


def test_identity_mismatch_fails_before_model_or_milvus(monkeypatch):
    monkeypatch.setenv("INTERNAL_TOKEN", "test-internal-key-at-least-32-characters")

    def forbidden(*args):
        raise AssertionError("Must not query incompatible index")

    monkeypatch.setattr(api.retrieval, "recall", forbidden)
    with TestClient(api.app) as client:
        response = client.post(
            "/internal/v1/recall",
            headers={"X-Internal-Token": "test-internal-key-at-least-32-characters"},
            json={
                "tenant_id": "synthetic",
                "version_ids": ["synthetic"],
                "query": "q",
                "model_configuration": configuration().model_dump(mode="json"),
                "expected_model_identity": "different-index-identity",
            },
        )
    assert response.status_code == 503


def test_generation_context_does_not_cross_threadpool_yields(monkeypatch):
    from careflow.protocol_v1 import Generate

    seen = []

    def synthetic_stream(*args):
        for event in [{"text": "one"}, {"text": "two"}, {"done": True}]:
            seen.append(value("GENERATION_MODEL"))
            yield json.dumps(event) + "\n"

    monkeypatch.setattr(models, "generate_stream", synthetic_stream)
    body = Generate(
        query="synthetic",
        evidence=[{"id": "synthetic", "content": "synthetic"}],
        model_configuration=configuration(kind="GENERATION", model="stream-snapshot"),
    )
    # Exercise Starlette's real threadpool iterator, with a fresh context at each next().
    monkeypatch.setenv("INTERNAL_TOKEN", "test-internal-key-at-least-32-characters")
    with TestClient(api.app) as client:
        response = client.post(
            "/internal/v1/generate/stream",
            headers={"X-Internal-Token": "test-internal-key-at-least-32-characters"},
            json=body.model_dump(mode="json"),
        )
    assert [json.loads(line) for line in response.text.splitlines()] == [
        {"text": "one"},
        {"text": "two"},
        {"done": True},
    ]
    assert seen == ["stream-snapshot"] * 3
    assert value("GENERATION_MODEL") != "stream-snapshot"


def test_consumer_uses_claim_snapshot_without_serializing_secret_as_mask(monkeypatch):
    import httpx

    from careflow import consumer

    monkeypatch.setenv("INTERNAL_TOKEN", "fixture-internal-key")
    monkeypatch.setenv("EMBEDDING_MODEL", "changed-deployment-model")
    client_type = httpx.Client
    completed = []
    runtime = {
        "id": "configuration-id",
        "parsing": {"pdf_page_limit": 20},
        "chunking": {"target": 120, "maximum": 200, "overlap": 10},
        **{
            kind.lower(): {
                **configuration(kind=kind).model_dump(mode="json"),
                "api_key": "unmasked-fixture-secret",
            }
            for kind in ("EMBEDDING", "RERANK", "GENERATION")
        },
    }

    def handler(request):
        if request.url.path.endswith("/claim"):
            return httpx.Response(
                200,
                json={
                    "id": "job",
                    "lease_token": "lease",
                    "kind": "INDEX",
                    "tenant_id": "tenant",
                    "version_id": "version",
                    "filename": "synthetic.txt",
                    "pdf_page_limit": 500,
                    "configuration": runtime,
                },
            )
        if request.url.path.endswith("/chunks"):
            return httpx.Response(200, json=[{"id": "chunk", "content": "synthetic"}])
        if request.url.path.endswith("/complete"):
            completed.append(json.loads(request.content))
        return httpx.Response(200, json={})

    def index(*args):
        assert value("EMBEDDING_MODEL") == "snapshot-model"
        assert value("EMBEDDING_API_KEY") == "unmasked-fixture-secret"
        return {
            "verified": True,
            "model_identity": models.identity(),
            "embedding_tokens": 3,
        }

    monkeypatch.setattr(
        consumer.httpx,
        "Client",
        lambda **kwargs: client_type(transport=httpx.MockTransport(handler), **kwargs),
    )
    monkeypatch.setattr(consumer.retrieval, "index", index)
    consumer.run_job("job")
    assert completed[0]["verified"]
    assert value("EMBEDDING_MODEL") == "changed-deployment-model"
    assert "unmasked-fixture-secret" not in json.dumps(completed)
