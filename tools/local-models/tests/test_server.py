import threading
from concurrent.futures import ThreadPoolExecutor

import pytest
from fastapi.testclient import TestClient

from model_registry import MODELS
from model_server import create_app

TOKEN = "synthetic-local-model-token-32-characters"
HEADERS = {"Authorization": "Bearer " + TOKEN}


class FixtureEngine:
    dimension = 2

    def embed(self, texts):
        return [[1.0, 0.0] for _ in texts], 3 * len(texts)

    def rerank(self, query, documents):
        return [0.1, 0.9][: len(documents)], 7


def test_authentication_model_identity_input_and_actual_usage_contract():
    with TestClient(create_app(FixtureEngine(), TOKEN)) as client:
        assert client.get("/health").status_code == 401
        body = {"model": MODELS["embedding"][0], "input": ["synthetic"]}
        response = client.post("/v1/embeddings", headers=HEADERS, json=body)
        assert response.json()["usage"] == {"total_tokens": 3}
        assert response.json()["data"][0]["index"] == 0
        body["model"] = "unregistered"
        assert (
            client.post("/v1/embeddings", headers=HEADERS, json=body).status_code == 400
        )
        body["input"] = [""]
        assert (
            client.post("/v1/embeddings", headers=HEADERS, json=body).status_code == 422
        )
        response = client.post(
            "/v1/rerank",
            headers=HEADERS,
            json={
                "model": MODELS["rerank"][0],
                "query": "synthetic",
                "documents": ["first", "second"],
                "top_n": 1,
            },
        )
        assert response.json()["results"] == [{"index": 1, "relevance_score": 0.9}]


def test_concurrency_is_bounded_and_gate_releases_after_execution():
    entered, release = threading.Event(), threading.Event()

    class BlockingEngine(FixtureEngine):
        def embed(self, texts):
            entered.set()
            assert release.wait(5)
            return super().embed(texts)

    with (
        TestClient(create_app(BlockingEngine(), TOKEN)) as client,
        ThreadPoolExecutor(max_workers=1) as pool,
    ):
        body = {"model": MODELS["embedding"][0], "input": ["synthetic"]}
        first = pool.submit(client.post, "/v1/embeddings", headers=HEADERS, json=body)
        try:
            assert entered.wait(5)
            assert (
                client.post("/v1/embeddings", headers=HEADERS, json=body).status_code
                == 429
            )
        finally:
            release.set()
        assert first.result().status_code == 200
        assert (
            client.post("/v1/embeddings", headers=HEADERS, json=body).status_code == 200
        )


def test_bad_outputs_and_exceptions_never_become_fabricated_success():
    class BadEngine(FixtureEngine):
        def embed(self, texts):
            return [[float("nan"), 0]], 1

        def rerank(self, query, documents):
            raise RuntimeError("sensitive fixture must not be exposed")

    with TestClient(create_app(BadEngine(), TOKEN)) as client:
        assert (
            client.post(
                "/v1/embeddings",
                headers=HEADERS,
                json={"model": MODELS["embedding"][0], "input": ["synthetic"]},
            ).status_code
            == 503
        )
        response = client.post(
            "/v1/rerank",
            headers=HEADERS,
            json={
                "model": MODELS["rerank"][0],
                "query": "synthetic",
                "documents": ["text"],
                "top_n": 1,
            },
        )
        assert response.status_code == 503
        assert "sensitive" not in response.text
    with pytest.raises(RuntimeError):
        create_app(FixtureEngine(), "")


def test_weight_checksum_mismatch_is_rejected(tmp_path, monkeypatch):
    import model_registry

    (tmp_path / "model.safetensors").write_bytes(b"invalid synthetic weights")
    monkeypatch.setattr(model_registry, "model_path", lambda kind: tmp_path)
    with pytest.raises(ValueError, match="checksum"):
        model_registry.verify_snapshot("embedding")


def test_explicit_container_model_directory_does_not_depend_on_repository_layout(
    monkeypatch,
):
    import model_registry

    monkeypatch.setenv("LOCAL_MODEL_DIRECTORY", "/models")
    monkeypatch.setattr(model_registry, "__file__", "/app/model_registry.py")
    assert str(model_registry.model_path("embedding")).startswith("/models/")
