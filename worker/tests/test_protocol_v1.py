import uuid

from fastapi.testclient import TestClient

from careflow.api import app


def client(monkeypatch):
    monkeypatch.setenv("INTERNAL_TOKEN", "synthetic-service-contract-key-32-characters")
    return TestClient(
        app,
        headers={"X-Internal-Token": "synthetic-service-contract-key-32-characters"},
    )


def test_internal_contract_rejects_unknown_fields_modes_and_untyped_evidence(
    monkeypatch,
):
    c = client(monkeypatch)
    request = {
        "tenant_id": str(uuid.uuid4()),
        "version_ids": [],
        "query": "fixture",
        "mode": "unknown",
    }
    assert c.post("/internal/v1/recall", json=request).status_code == 422
    request.update(mode="keyword", untrusted="ignored?")
    assert c.post("/internal/v1/recall", json=request).status_code == 422
    assert (
        c.post(
            "/internal/v1/generate/stream",
            json={"query": "fixture", "evidence": [{"id": "a"}]},
        ).status_code
        == 422
    )


def test_empty_authorized_scope_and_tokenize_follow_response_schema(monkeypatch):
    c = client(monkeypatch)
    response = c.post(
        "/internal/v1/recall",
        json={
            "tenant_id": str(uuid.uuid4()),
            "version_ids": [],
            "query": "fixture",
            "mode": "keyword",
        },
    )
    assert response.status_code == 200
    assert response.json()["fused"] == []
    result = c.post("/internal/v1/tokenize", json={"text": "知识测试"})
    assert result.status_code == 200 and result.json()["token_count"] > 0


def test_bad_recall_output_is_explicit_failure(monkeypatch):
    import careflow.api as api

    monkeypatch.setattr(api.retrieval, "recall", lambda *args: {"fused": []})
    c = client(monkeypatch)
    response = c.post(
        "/internal/v1/recall",
        json={"tenant_id": str(uuid.uuid4()), "version_ids": [], "query": "fixture"},
    )
    assert response.status_code == 503
    assert response.json()["detail"] == "RETRIEVAL_UNAVAILABLE"


def test_invalid_rerank_output_is_explicit_service_error(monkeypatch):
    import careflow.api as api

    monkeypatch.setattr(
        api.models, "rerank", lambda *args: [{"id": "a", "score": float("nan")}]
    )
    c = client(monkeypatch)
    response = c.post(
        "/internal/v1/rerank",
        json={"query": "fixture", "candidates": [{"id": "a", "content": "source"}]},
    )
    assert response.status_code == 503


def test_generation_events_reject_ambiguous_types_and_stop_at_done(monkeypatch):
    import careflow.api as api

    c = client(monkeypatch)
    body = {"query": "fixture", "evidence": [{"id": "a", "content": "source"}]}
    monkeypatch.setattr(
        api.models,
        "generate_stream",
        lambda *args: iter(['{"text":"ok"}\n', '{"done":true}\n', '{"text":"late"}\n']),
    )
    response = c.post("/internal/v1/generate/stream", json=body)
    assert '"late"' not in response.text and '"done":true' in response.text
    monkeypatch.setattr(
        api.models,
        "generate_stream",
        lambda *args: iter(['{"text":"ambiguous","done":true}\n']),
    )
    response = c.post("/internal/v1/generate/stream", json=body)
    assert '"error"' in response.text and "ambiguous" not in response.text


def test_cleanup_adapter_failure_is_sanitized(monkeypatch):
    import careflow.api as api

    c = client(monkeypatch)
    monkeypatch.setattr(api.retrieval, "client", lambda: object())
    monkeypatch.setattr(
        api.index_cleanup,
        "purge_version",
        lambda *args: {
            "verified": True,
            "compactions": [{"collection": "synthetic", "job_id": 0}],
        },
    )
    response = c.post(
        "/internal/v1/index/purge-version",
        json={"tenant_id": str(uuid.uuid4()), "version_id": str(uuid.uuid4())},
    )
    assert response.status_code == 503
    assert response.json() == {"detail": "INDEX_PURGE_UNAVAILABLE"}


def test_evidence_budget_uses_real_tokens_not_characters_and_blocks_generation(
    monkeypatch,
):
    from careflow.chunking import tokens

    c = client(monkeypatch)
    english = "word " * 1500
    chinese = "复杂" * 1500
    response = c.post(
        "/internal/v1/context/tokens",
        json={
            "candidates": [
                {"id": "en", "content": english},
                {"id": "zh", "content": chinese},
            ]
        },
    )
    assert response.status_code == 200
    assert response.json()["counts"] == [
        {"id": "en", "token_count": tokens(english)},
        {"id": "zh", "token_count": tokens(chinese)},
    ]
    assert len(english) > 6000 and tokens(english) < 6000
    response = c.post(
        "/internal/v1/generate/stream",
        json={
            "query": "synthetic",
            "evidence": [{"id": str(i), "content": chinese} for i in range(3)],
        },
    )
    assert response.status_code == 422
    assert response.json() == {"detail": "EVIDENCE_TOKEN_LIMIT"}


def test_rerank_timeout_obeys_explicit_degradation_policy(monkeypatch):
    import httpx

    import careflow.api as api

    def timeout(*args):
        raise httpx.ReadTimeout("synthetic private endpoint must not be disclosed")

    monkeypatch.setattr(api.models, "rerank", timeout)
    c = client(monkeypatch)
    body = {"query": "fixture", "candidates": [{"id": "a", "content": "source"}]}
    denied = c.post("/internal/v1/rerank", json=body)
    assert denied.status_code == 503
    assert denied.json()["detail"] == "RERANK_UNAVAILABLE"
    body["allow_degraded"] = True
    allowed = c.post("/internal/v1/rerank", json=body)
    assert allowed.status_code == 200
    assert allowed.json()["degraded"] is True
    assert allowed.json()["results"] == [{"id": "a", "score": None}]
    assert "private endpoint" not in allowed.text
