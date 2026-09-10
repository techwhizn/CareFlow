"""Behavior tests with an explicit in-memory vector-store double, no model calls."""

import json
import uuid

import httpx
import pytest

from careflow import embedding_cache, models


class Store:
    def __init__(self):
        self.rows = {}

    def has_collection(self, name):
        return True

    def query(self, collection_name, filter, **kwargs):
        keys = json.loads(filter.removeprefix("id in "))
        return [
            row
            for key, row in self.rows.get(collection_name, {}).items()
            if key in keys
        ]

    def upsert(self, collection_name, data):
        self.rows.setdefault(collection_name, {}).update(
            {row["id"]: row for row in data}
        )


@pytest.fixture
def configured(monkeypatch):
    monkeypatch.setenv("EMBEDDING_MODEL", "synthetic-model")
    monkeypatch.setenv("EMBEDDING_REVISION", "immutable-revision")
    monkeypatch.setenv("EMBEDDING_DIMENSIONS", "2")
    monkeypatch.setenv("EMBEDDING_BASE_URL", "https://model.invalid/v1")


def test_content_reuse_across_new_chunk_ids_is_tenant_and_model_scoped(
    configured, monkeypatch
):
    store, tenant, calls = Store(), str(uuid.uuid4()), []

    def embed(texts, **kwargs):
        calls.append(texts)
        return [[1.0, 2.0] for _ in texts], len(texts) * 5

    monkeypatch.setattr(models, "embed", embed)
    _, first = embedding_cache.vectors(store, tenant, ["a", "b", "a"])
    assert first == {
        "embedding_tokens": 10,
        "indexed_chunks": 3,
        "embedded_texts": 2,
        "reused_chunks": 1,
    }
    _, changed = embedding_cache.vectors(store, tenant, ["a", "edited", "a"])
    assert changed["embedded_texts"] == 1 and changed["reused_chunks"] == 2
    assert calls == [["a", "b"], ["edited"]]
    _, unchanged = embedding_cache.vectors(store, tenant, ["a", "edited"])
    assert unchanged["embedding_tokens"] == 0 and unchanged["embedded_texts"] == 0
    embedding_cache.vectors(store, str(uuid.uuid4()), ["a"])
    monkeypatch.setenv("EMBEDDING_REVISION", "other-revision")
    embedding_cache.vectors(store, tenant, ["a"])
    assert calls[-2:] == [["a"], ["a"]]


def test_successful_batches_survive_later_failure_and_unknown_usage_stays_unknown(
    configured, monkeypatch
):
    store, tenant, calls = Store(), str(uuid.uuid4()), []
    texts = [str(i) for i in range(33)]

    def embed(batch, **kwargs):
        calls.append(batch)
        if len(calls) == 2:
            raise RuntimeError("synthetic upstream failure")
        return [[1.0, 2.0] for _ in batch], None

    monkeypatch.setattr(models, "embed", embed)
    with pytest.raises(RuntimeError):
        embedding_cache.vectors(store, tenant, texts)
    _, result = embedding_cache.vectors(store, tenant, texts)
    assert calls[-1] == ["32"]
    assert result["reused_chunks"] == 32 and result["embedding_tokens"] is None
    list(store.rows.values())[0][next(iter(list(store.rows.values())[0]))]["text"] = (
        "corrupt"
    )
    with pytest.raises(RuntimeError, match="integrity"):
        embedding_cache.vectors(store, tenant, texts)


@pytest.mark.parametrize(
    "usage,expected",
    [
        (None, None),
        ({}, None),
        ({"total_tokens": True}, None),
        ({"total_tokens": -1}, None),
        ({"total_tokens": 7}, 7),
    ],
)
def test_adapter_never_invents_missing_usage(configured, monkeypatch, usage, expected):
    events = []
    original = httpx.Client

    def handler(request):
        return httpx.Response(
            200, json={"data": [{"index": 0, "embedding": [1, 2]}], "usage": usage}
        )

    monkeypatch.setattr(
        models.httpx,
        "Client",
        lambda **kwargs: original(transport=httpx.MockTransport(handler), **kwargs),
    )
    _, consumed = models.embed(
        ["synthetic"], record_call=lambda *event: events.append(event)
    )
    assert consumed == expected
    assert [event[1] for event in events] == ["STARTED", "SUCCEEDED"]
    assert events[-1][-1] == expected


def test_call_is_recorded_before_invalid_vector_failure(configured, monkeypatch):
    events = []
    original = httpx.Client
    monkeypatch.setattr(
        models.httpx,
        "Client",
        lambda **kwargs: original(
            transport=httpx.MockTransport(
                lambda request: httpx.Response(
                    200,
                    json={
                        "data": [{"index": 0, "embedding": [1]}],
                        "usage": {"total_tokens": 9},
                    },
                )
            ),
            **kwargs,
        ),
    )
    with pytest.raises(models.ModelUnavailable):
        models.embed(["synthetic"], record_call=lambda *event: events.append(event))
    assert events[-1][1:] == ("SUCCEEDED", 1, 9)
