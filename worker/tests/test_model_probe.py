"""HTTP fixtures exercise failure detection, not real-model acceptance."""

import json

import httpx
import pytest

from careflow import models
from careflow.model_probe import check_models


@pytest.fixture
def adapter_http(monkeypatch):
    client = httpx.Client
    for kind in ("EMBEDDING", "RERANK", "GENERATION"):
        monkeypatch.setenv(kind + "_BASE_URL", "https://fixture.invalid/v1")
        monkeypatch.setenv(kind + "_MODEL", "synthetic-model")
        monkeypatch.setenv(kind + "_API_KEY", "secret-not-for-reports")
    monkeypatch.setenv("EMBEDDING_REVISION", "synthetic-revision")
    monkeypatch.setenv("EMBEDDING_DIMENSIONS", "2")

    def install(handler):
        monkeypatch.setattr(
            models.httpx,
            "Client",
            lambda **kwargs: client(transport=httpx.MockTransport(handler), **kwargs),
        )

    return install


def valid_response(request):
    if request.url.path.endswith("embeddings"):
        return httpx.Response(
            200,
            json={
                "data": [{"index": i, "embedding": [0.2, 0.8]} for i in range(2)],
                "usage": {"total_tokens": 10},
            },
        )
    if request.url.path.endswith("rerank"):
        return httpx.Response(
            200, json={"results": [{"index": 0, "relevance_score": 0.9}]}
        )
    return httpx.Response(
        200,
        text='data: {"choices":[{"delta":{"content":"synthetic"}}]}\n\ndata: [DONE]\n\n',
    )


def test_probe_reuses_adapters_and_reports_no_content_or_credentials(adapter_http):
    adapter_http(valid_response)
    report = check_models()
    assert report["complete"]
    assert report["checks"][0]["dimensions"] == 2
    assert report["checks"][0]["input_tokens"] == 10
    assert report["checks"][2]["stream_done"]
    assert "secret-not-for-reports" not in json.dumps(report)
    assert "content" not in json.dumps(report)


@pytest.mark.parametrize("status", [401, 403, 429, 503])
def test_http_error_is_redacted_and_other_probes_continue(adapter_http, status):
    adapter_http(lambda request: httpx.Response(status, text="secret-not-for-reports"))
    report = check_models()
    assert not report["complete"] and len(report["checks"]) == 3
    assert all(row["http_status"] == status for row in report["checks"])
    assert "secret-not-for-reports" not in json.dumps(report)


def test_timeout_is_explicit(adapter_http):
    def timeout(request):
        raise httpx.ReadTimeout("private endpoint", request=request)

    adapter_http(timeout)
    assert all(row["error"] == "MODEL_TIMEOUT" for row in check_models()["checks"])


@pytest.mark.parametrize("failure", ["dimension", "indexes", "nan", "incomplete"])
def test_invalid_model_format_cannot_pass(adapter_http, failure):
    def handler(request):
        if request.url.path.endswith("embeddings") and failure == "dimension":
            return httpx.Response(
                200,
                json={
                    "data": [
                        {"index": 0, "embedding": [1]},
                        {"index": 1, "embedding": [1]},
                    ]
                },
            )
        if request.url.path.endswith("embeddings") and failure == "indexes":
            return httpx.Response(
                200,
                json={
                    "data": [
                        {"index": 0, "embedding": [1, 0]},
                        {"index": 0, "embedding": [1, 0]},
                    ]
                },
            )
        if request.url.path.endswith("rerank") and failure == "nan":
            return httpx.Response(
                200, content=b'{"results":[{"index":0,"relevance_score":NaN}]}'
            )
        if request.url.path.endswith("chat/completions") and failure == "incomplete":
            return httpx.Response(
                200, text='data: {"choices":[{"delta":{"content":"partial"}}]}\n\n'
            )
        return valid_response(request)

    adapter_http(handler)
    assert not check_models()["complete"]


@pytest.mark.parametrize(
    "reported,expected",
    [(12, 12), (0, 0), (None, None), (True, None), (-1, None), ("12", None)],
)
def test_rerank_reports_only_actual_valid_usage(adapter_http, reported, expected):
    adapter_http(
        lambda request: httpx.Response(
            200,
            json={
                "results": [{"index": 0, "relevance_score": 0.9}],
                "usage": {"total_tokens": reported},
            },
        )
    )
    consumption = []
    result = models.rerank(
        "fixture", [{"id": "a", "content": "source"}], consumption.append
    )
    assert result == [{"id": "a", "score": 0.9}]
    assert consumption == [expected]
