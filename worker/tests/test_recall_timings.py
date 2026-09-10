import uuid

import pytest

from careflow import retrieval


@pytest.mark.parametrize(
    "mode,degraded", [("hybrid", False), ("keyword", False), ("hybrid", True)]
)
def test_recall_timing_excludes_only_embedding_and_preserves_lanes(
    monkeypatch, mode, degraded
):
    clock = [0.0]
    lanes = []

    class Index:
        def has_collection(self, name):
            clock[0] += 0.1
            return True

        def search(self, **kwargs):
            clock[0] += 0.2
            lanes.append(kwargs["anns_field"])
            return [[{"id": "synthetic-chunk", "distance": 1.0}]]

    def embed(texts):
        clock[0] += 5
        if degraded:
            raise retrieval.models.ModelUnavailable("synthetic model failure")
        return [[1.0]], 1

    monkeypatch.setattr(retrieval.time, "perf_counter", lambda: clock[0])
    monkeypatch.setattr(retrieval, "client", Index)
    monkeypatch.setattr(retrieval, "collection", lambda *args: "synthetic")
    monkeypatch.setattr(retrieval.models, "embed", embed)
    result = retrieval.recall(
        str(uuid.uuid4()), [str(uuid.uuid4())], "q", mode, degraded
    )
    assert lanes == (
        ["dense", "sparse"] if mode == "hybrid" and not degraded else ["sparse"]
    )
    assert result["timings_ms"]["embedding"] == (0 if mode == "keyword" else 5000)
    assert result["timings_ms"]["pure_retrieval"] == pytest.approx(
        100 + 200 * len(lanes)
    )
    assert result["degraded"] == degraded
    assert result["fused"][0]["id"] == "synthetic-chunk"
