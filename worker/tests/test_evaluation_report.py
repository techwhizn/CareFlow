import pytest

from careflow.evaluation import apply_reviews, compare, metrics, summarize


def test_top10_and_final6_are_distinct_and_unknown_cost_is_not_zero():
    row = {
        "case_id": "one",
        "status": 200,
        "latency_ms": 20,
        "retrieval_top10": metrics(
            ["correct"], [str(i) for i in range(9)] + ["correct"], 10
        ),
        "final_evidence": metrics(["correct"], [str(i) for i in range(6)], 6),
    }
    report = summarize([row])
    assert report["hit_at_10"] == 1 and report["hit_at_6"] == 0
    assert report["mrr_at_10"] == 0.1
    assert report["unknown_cost_cases"] == 1
    assert report["known_customer_costs_by_currency"] == {}
    assert report["human_review"]["answer_correctness"] is None


def test_comparison_requires_same_labels_and_execution_phases():
    a = {
        "dataset_digest": "same",
        "results": [],
        "summary": {"hit_at_10": 0.5},
        "answer_generation_enabled": False,
    }
    b = {**a, "summary": {"hit_at_10": 0.75}}
    assert compare(a, b)["deltas_second_minus_first"]["hit_at_10"] == 0.25
    with pytest.raises(ValueError):
        compare(a, {**b, "dataset_digest": "changed"})
    with pytest.raises(ValueError):
        compare(a, {**b, "answer_generation_enabled": True})


def test_human_scores_only_exist_after_explicit_valid_ratings():
    report = {
        "answer_generation_enabled": True,
        "summary": {},
        "results": [
            {
                "case_id": "one",
                "answer_completed": True,
                "answerability": "UNANSWERABLE",
            }
        ],
    }
    review = {
        "case_id": "one",
        "reviewer": "Synthetic test reviewer",
        "human_reviewed": True,
        "correct": True,
        "supported": True,
        "citations_correct": True,
        "refused": True,
    }
    scored = apply_reviews(report, [review])
    assert scored["summary"]["human_review"]["unanswerable_refusal_rate"] == 1
    assert scored["summary"]["human_review"]["answerable_false_refusal_rate"] is None
    assert report["summary"] == {}
    with pytest.raises(ValueError):
        apply_reviews(report, [{**review, "human_reviewed": False}])
    with pytest.raises(ValueError):
        apply_reviews(report, [review, review])


def test_resume_preserves_failures_and_checkpoints_each_new_case(monkeypatch):
    from contextlib import contextmanager
    from pathlib import Path

    monkeypatch.syspath_prepend(str(Path(__file__).resolve().parents[2] / "sdk/python"))
    import careflow_sdk

    from careflow.evaluation import run

    calls, saved = [], []

    class Client:
        def __init__(self, *args):
            pass

        def __enter__(self):
            return self

        def __exit__(self, *args):
            pass

        def me(self):
            return {"subject_id": "person", "role": "OWNER"}

        def search(self, query):
            calls.append(query.query)
            if query.query == "fails":
                raise careflow_sdk.ApiError(429, "RATE_LIMITED", "rate-limit-trace")
            return {
                "query_record_id": "query",
                "evidence": [{"id": "source"}],
                "publication_versions": [],
                "configuration_id": "config",
                "degraded": False,
                "evidence_status": "FOUND",
            }

        def request_cost(self, *args, **kwargs):
            raise careflow_sdk.ApiError(403, "FORBIDDEN")

        @contextmanager
        def answer(self, query):
            def events():
                yield careflow_sdk.Event("delta", {"text": "partial"})
                raise careflow_sdk.StreamError("interrupted")

            yield events()

    monkeypatch.setattr(careflow_sdk, "Client", Client)
    cases = [
        {
            "id": name,
            "question": name,
            "expected_chunk_ids": ["source"],
            "test_subject_id": "person",
            "test_subject_kind": "MEMBER",
        }
        for name in ("previous", "fails", "interrupted")
    ]
    previous = {"case_id": "previous", "status": 503, "latency_ms": 1}
    report = run(
        cases,
        "unused",
        "test",
        answers=True,
        initial_results=[previous],
        checkpoint=lambda rows: saved.append([dict(row) for row in rows]),
    )
    assert calls == ["fails", "interrupted"]
    assert [len(rows) for rows in saved] == [2, 3]
    assert report["results"][0] == previous
    assert report["results"][1]["status"] == 429
    assert report["results"][1]["request_id"] == "rate-limit-trace"
    assert report["results"][2]["answer"] == "partial"
    assert report["results"][2]["answer_completed"] is False
    assert report["summary"]["human_review"]["answer_correctness"] is None
    assert report["complete"] is True
    with pytest.raises(ValueError, match="ordered prefix"):
        run(cases, "unused", "test", initial_results=[{"case_id": "wrong"}])
