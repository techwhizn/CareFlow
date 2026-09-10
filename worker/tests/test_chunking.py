import json

import httpx
import pytest

from careflow import models
from careflow.chunking import chunk, tokens
from careflow.parsing_types import Block, InvalidFile


def test_title_prefix_is_counted_and_every_unicode_span_is_exact():
    text = "第一句。\n\n第二段：知识与😀，" * 80
    rows = chunk(
        [Block(text, {"title_path": ["手册", "设备"]})],
        target=60,
        maximum=80,
        overlap=4,
    )
    assert len(rows) > 10
    covered = set()
    for row in rows:
        location = json.loads(row["location"])
        assert row["content"].startswith("手册 > 设备\n")
        assert row["token_count"] == tokens(row["content"]) <= 80
        assert (
            row["source_text"] == text[location["block_start"] : location["block_end"]]
        )
        covered.update(range(location["block_start"], location["block_end"]))
        assert "�" not in row["content"]
    assert covered == set(range(len(text)))


def test_table_header_is_repeated_counted_and_source_is_not_fabricated():
    text = "型号: CF-100 | 说明: " + "连接失败后检查电源。" * 80
    rows = chunk(
        [
            Block(
                text,
                {
                    "type": "table",
                    "sheet": "故障表",
                    "headers": ["型号", "说明（伏特）"],
                },
            )
        ],
        target=80,
        maximum=100,
        overlap=0,
    )
    assert len(rows) > 2
    assert all("说明（伏特）" in row["content"] for row in rows)
    assert "".join(row["source_text"] for row in rows) == text


def test_provider_budget_recursively_splits_without_dropping_or_reordering_source(
    monkeypatch,
):
    monkeypatch.setattr(
        models, "input_tokens", lambda texts, mode: ([len(t) + 2 for t in texts], 35)
    )
    text = "abcdefghijklmnopqrstuvwxyz。" * 8
    rows = chunk(
        [Block(text, {"title_path": ["标题"]})],
        target=100,
        maximum=120,
        overlap=0,
        model_tokenizer="provider",
        model_maximum=100,
    )
    assert "".join(row["source_text"] for row in rows) == text
    assert all(len(row["content"]) + 2 <= 35 for row in rows)
    assert all(json.loads(row["location"])["model_token_count"] <= 35 for row in rows)


def test_unfittable_context_fails_and_special_token_strings_are_plain_source():
    with pytest.raises(InvalidFile):
        chunk(
            [Block("body", {"title_path": ["very long title " * 30]})],
            target=10,
            maximum=20,
            overlap=0,
        )
    assert chunk([Block("<|endoftext|> untrusted source", {})])[0][
        "content"
    ].startswith("<|endoftext|>")


def test_exact_token_mode_and_recursive_mode_keep_distinct_boundaries():
    text = "first paragraph.\n\n" + "second paragraph has enough words. " * 20
    recursive = chunk(
        [Block(text, {})], target=15, maximum=20, overlap=0, strategy="recursive"
    )
    fixed = chunk([Block(text, {})], target=15, maximum=20, overlap=0, strategy="token")
    assert [r["source_text"] for r in recursive] != [r["source_text"] for r in fixed]
    assert "".join(r["source_text"] for r in recursive) == text
    assert "".join(r["source_text"] for r in fixed) == text


def test_model_tokenizer_refuses_wrong_revision_and_malformed_counts(monkeypatch):
    monkeypatch.setenv("EMBEDDING_BASE_URL", "https://model.invalid/v1")
    monkeypatch.setenv("EMBEDDING_MODEL", "fixture")
    monkeypatch.setenv("EMBEDDING_REVISION", "pinned")
    original = httpx.Client
    payload = {
        "model": "fixture",
        "revision": "other",
        "counts": [3],
        "max_input_tokens": 512,
    }
    monkeypatch.setattr(
        models.httpx,
        "Client",
        lambda **kw: original(
            transport=httpx.MockTransport(lambda r: httpx.Response(200, json=payload)),
            **kw,
        ),
    )
    with pytest.raises(models.ModelUnavailable):
        models.input_tokens(["text"], "provider")
    payload["revision"] = "pinned"
    payload["counts"] = [True]
    with pytest.raises(models.ModelUnavailable):
        models.input_tokens(["text"], "provider")
    payload["counts"] = [8]
    assert models.input_tokens(["text"], "provider") == ([8], 512)


def test_provider_subdivision_preserves_block_order_when_only_first_block_is_oversize(
    monkeypatch,
):
    monkeypatch.setattr(
        models, "input_tokens", lambda texts, mode: ([len(t) + 2 for t in texts], 30)
    )
    rows = chunk(
        [Block("a" * 100, {}), Block("second", {})],
        overlap=0,
        model_tokenizer="provider",
    )
    assert "".join(row["source_text"] for row in rows) == "a" * 100 + "second"
    assert json.loads(rows[-1]["location"])["block_ordinal"] == 1


def test_index_rejects_edited_model_overflow_before_vector_store_or_embedding(
    monkeypatch,
):
    from careflow import retrieval
    from careflow.protocol_v1 import ChunkingConfiguration

    monkeypatch.setattr(models, "input_tokens", lambda texts, mode: ([600], 512))

    def forbidden(*args):
        raise AssertionError("No index side effect before input validation")

    monkeypatch.setattr(retrieval, "ensure_collection", forbidden)
    monkeypatch.setattr(models, "embed", forbidden)
    policy = ChunkingConfiguration(
        target=400, maximum=600, overlap=20, model_tokenizer="provider"
    )
    with pytest.raises(ValueError, match="model input limit"):
        retrieval.index(
            "tenant",
            "version",
            [{"id": "chunk", "content": "short logical input"}],
            policy,
        )
