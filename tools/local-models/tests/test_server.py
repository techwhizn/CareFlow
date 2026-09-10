import threading
from concurrent.futures import ThreadPoolExecutor

import pytest
from fastapi.testclient import TestClient

from model_registry import MODELS
from model_server import create_app, rerank_batch_size

TOKEN = "synthetic-local-model-token-32-characters"
HEADERS = {"Authorization": "Bearer " + TOKEN}


@pytest.mark.parametrize("value", ["0", "41", "not-an-integer", "1.5"])
def test_invalid_batch_size_fails_instead_of_silent_fallback(monkeypatch, value):
    monkeypatch.setenv("LOCAL_RERANK_BATCH_SIZE", value)
    with pytest.raises(ValueError):
        rerank_batch_size()


def test_batch_size_is_bounded_and_default_preserves_existing_memory_budget(
    monkeypatch,
):
    monkeypatch.delenv("LOCAL_RERANK_BATCH_SIZE", raising=False)
    assert rerank_batch_size() == 4
    monkeypatch.setenv("LOCAL_RERANK_BATCH_SIZE", "40")
    assert rerank_batch_size() == 40


class FixtureEngine:
    dimension = 2

    def embed(self, texts):
        return [[1.0, 0.0] for _ in texts], 3 * len(texts)

    def rerank(self, query, documents):
        return [0.1, 0.9][: len(documents)], 7

    def token_counts(self, texts):
        return [len(text) + 2 for text in texts]


def test_tokenizer_reports_pinned_identity_and_includes_special_tokens():
    with TestClient(create_app(FixtureEngine(), TOKEN)) as client:
        body = {"model": MODELS["embedding"][0], "input": ["ab", "abcdef"]}
        assert client.post("/v1/tokenize", json=body).status_code == 401
        response = client.post("/v1/tokenize", headers=HEADERS, json=body)
        assert response.json() == {
            "model": MODELS["embedding"][0],
            "revision": MODELS["embedding"][1],
            "counts": [4, 8],
            "max_input_tokens": 512,
        }


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


def test_embedding_and_rerank_pair_windows_never_silently_truncate():
    from types import SimpleNamespace

    from fastapi import HTTPException

    from model_server import Models

    engine = Models.__new__(Models)
    observed = []

    def tokenizer(texts, **kwargs):
        observed.append(kwargs)
        return {"input_ids": SimpleNamespace(shape=(len(texts), 513))}

    engine.tokenizers = {"embedding": tokenizer, "rerank": tokenizer}
    for kind, pairs in [("embedding", None), ("rerank", ["passage"])]:
        with pytest.raises(HTTPException) as error:
            engine.encode(kind, ["query"], pairs)
        assert error.value.status_code == 400
        assert error.value.detail == "MODEL_INPUT_TOO_LONG"
    assert all(item["truncation"] is False for item in observed)
    assert observed[1]["text_pair"] == ["passage"]
