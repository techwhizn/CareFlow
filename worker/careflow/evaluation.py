"""Deterministic metrics and real public API evaluation; human ratings stay explicitly pending."""

import argparse
import hashlib
import json
import math
import os
import time
from pathlib import Path


def metrics(expected, retrieved, k=10):
    expected, retrieved = set(expected), list(dict.fromkeys(retrieved))[:k]
    if not expected:
        return {
            "recall": None,
            "hit": None,
            "mrr": None,
            "empty_correct": not retrieved,
        }
    matched = expected.intersection(retrieved)
    first = next(
        (rank for rank, value in enumerate(retrieved, 1) if value in expected), None
    )
    return {
        "recall": len(matched) / len(expected),
        "hit": int(bool(matched)),
        "mrr": 1 / first if first else 0,
        "empty_correct": None,
    }


def percentile(values, fraction):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)] if ordered else None


def summarize(results):
    summary = {
        "cases": len(results),
        "failure_rate": sum(r["status"] != 200 for r in results) / len(results)
        if results
        else None,
    }
    for field, suffix in [("retrieval_top10", "at_10"), ("final_evidence", "at_6")]:
        eligible = [
            r[field] for r in results if r.get(field) and r[field]["recall"] is not None
        ]
        summary[field + "_eligible"] = len(eligible)
        for metric in ("recall", "hit", "mrr"):
            summary[f"{metric}_{suffix}"] = (
                sum(r[metric] for r in eligible) / len(eligible) if eligible else None
            )
    latencies = [r["latency_ms"] for r in results]
    summary.update(
        p50_ms=percentile(latencies, 0.5), p95_ms=percentile(latencies, 0.95)
    )
    generated = [r for r in results if r.get("answer_attempted")]
    summary["answer_failure_rate"] = (
        sum(not r.get("answer_completed", False) for r in generated) / len(generated)
        if generated
        else None
    )
    for phase in ("search", "answer"):
        times = [
            r[phase + "_latency_ms"] for r in results if phase + "_latency_ms" in r
        ]
        summary[phase + "_p50_ms"] = percentile(times, 0.5)
        summary[phase + "_p95_ms"] = percentile(times, 0.95)
    summary["human_review"] = {
        "reviewed": 0,
        "answer_correctness": None,
        "evidence_support": None,
        "citation_accuracy": None,
        "unanswerable_refusal_rate": None,
        "answerable_false_refusal_rate": None,
    }
    summary["known_customer_costs_by_currency"] = {}
    summary["unknown_cost_cases"] = 0
    from decimal import Decimal

    for row in results:
        executions = [row.get("cost") or {}]
        if row.get("answer_attempted"):
            executions.append(row.get("answer_cost") or {})
        unknown = False
        for cost in executions:
            customer = cost.get("customer", {})
            if customer.get("amount") is None or not cost.get("currency"):
                unknown = True
            else:
                currency = cost["currency"]
                value = Decimal(
                    summary["known_customer_costs_by_currency"].get(currency, "0")
                ) + Decimal(customer["amount"])
                summary["known_customer_costs_by_currency"][currency] = str(value)
        summary["unknown_cost_cases"] += int(unknown)
    return summary


def apply_reviews(report, reviews):
    """Apply explicit human ratings; never derive semantic correctness from model text."""
    if not report.get("answer_generation_enabled"):
        raise ValueError("Answer quality review requires an answer-generation run")
    import copy

    result = copy.deepcopy(report)
    by_id = {r["case_id"]: r for r in result["results"]}
    seen = set()
    for review in reviews:
        key = review.get("case_id")
        if (
            key not in by_id
            or key in seen
            or not review.get("reviewer")
            or review.get("human_reviewed") is not True
        ):
            raise ValueError("Each human review needs a unique known case and reviewer")
        if any(
            type(review.get(field)) is not bool
            for field in ("correct", "supported", "citations_correct", "refused")
        ):
            raise ValueError("Human ratings must be explicit booleans")
        if not by_id[key].get("answer_completed"):
            raise ValueError(
                "An incomplete answer cannot receive semantic quality ratings"
            )
        seen.add(key)
        by_id[key]["human_review"] = dict(review)
    rated = [r for r in result["results"] if isinstance(r.get("human_review"), dict)]

    def average(field, rows):
        return sum(r["human_review"][field] for r in rows) / len(rows) if rows else None

    unanswerable = [r for r in rated if r.get("answerability") == "UNANSWERABLE"]
    answerable = [r for r in rated if r.get("answerability") != "UNANSWERABLE"]
    result["summary"]["human_review"] = {
        "reviewed": len(rated),
        "answer_correctness": average("correct", rated),
        "evidence_support": average("supported", rated),
        "citation_accuracy": average("citations_correct", rated),
        "unanswerable_refusal_rate": average("refused", unanswerable),
        "answerable_false_refusal_rate": average("refused", answerable),
    }
    return result


def compare(first, second):
    if first.get("dataset_digest") != second.get("dataset_digest") or not first.get(
        "dataset_digest"
    ):
        raise ValueError("Comparison requires the identical dataset and labels")
    if first.get("answer_generation_enabled") != second.get(
        "answer_generation_enabled"
    ):
        raise ValueError("Comparison requires matching execution phases")
    left = {r["case_id"]: r for r in first["results"]}
    right = {r["case_id"]: r for r in second["results"]}
    if left.keys() != right.keys():
        raise ValueError("Comparison requires identical case IDs")
    deltas = {}
    for key in (
        "recall_at_10",
        "hit_at_10",
        "mrr_at_10",
        "recall_at_6",
        "hit_at_6",
        "mrr_at_6",
        "failure_rate",
        "p50_ms",
        "p95_ms",
    ):
        a, b = first["summary"].get(key), second["summary"].get(key)
        deltas[key] = b - a if a is not None and b is not None else None
    return {
        "dataset_digest": first["dataset_digest"],
        "first": first.get("label"),
        "second": second.get("label"),
        "deltas_second_minus_first": deltas,
        "changed_cases": [
            key
            for key in left
            if (
                left[key].get("retrieval_top10"),
                left[key].get("final_evidence"),
                left[key]["status"],
            )
            != (
                right[key].get("retrieval_top10"),
                right[key].get("final_evidence"),
                right[key]["status"],
            )
        ],
        "human_quality_comparison": "PENDING_HUMAN_REVIEW",
        "evidence_mapping_changed": first.get("evidence_mapping_digest")
        != second.get("evidence_mapping_digest"),
    }


def run(
    cases,
    base,
    token,
    *,
    mode="hybrid",
    label="experiment",
    answers=False,
    dataset_version=None,
    human_reviewed=False,
    progress=None,
    checkpoint=None,
    initial_results=(),
):
    # The SDK provides the shared bounded SSE parser; installing this optional evaluation tool
    # requires careflow-sdk, or PYTHONPATH=sdk/python when running from the repository.
    import httpx
    from careflow_sdk import ApiError, Client, Query, StreamError

    results = list(initial_results)
    if [r["case_id"] for r in results] != [c["id"] for c in cases[: len(results)]]:
        raise ValueError("Resume results must be an ordered prefix of this dataset")
    labels = [
        {
            k: v
            for k, v in c.items()
            if k not in ("expected_chunk_ids", "knowledge_base_ids", "application_id")
        }
        for c in cases
    ]
    digest = hashlib.sha256(
        json.dumps(labels, sort_keys=True, ensure_ascii=False).encode()
    ).hexdigest()
    mapping_digest = hashlib.sha256(
        json.dumps(cases, sort_keys=True, ensure_ascii=False).encode()
    ).hexdigest()
    with Client(base, token) as client:
        me = client.me()
        if not 1 <= len(cases) <= 500:
            raise ValueError("Evaluation requires 1 to 500 cases")
        for case in cases:
            if case.get("test_subject_id") != me["subject_id"] or case.get(
                "test_subject_kind"
            ) != ("APP" if me["role"] == "APPLICATION" else "MEMBER"):
                raise ValueError(
                    "Evaluation token does not match the case's declared test identity"
                )
        for case in cases[len(results) :]:
            started = time.monotonic()
            record = {
                "case_id": case["id"],
                "category": case.get("category"),
                "answerability": case.get("answerability"),
                "status": 0,
                "human_review": "PENDING",
                "reference_answer": case.get("reference_answer"),
                "question": case["question"],
                "test_subject_id": me["subject_id"],
            }
            query = Query(
                case["question"],
                knowledge_base_ids=tuple(case.get("knowledge_base_ids", [])),
                application_id=case.get("application_id"),
                mode=mode,
                limit=6,
                debug=True,
            )
            try:
                payload = client.search(query)
                final = [e["id"] for e in payload["evidence"]]
                ranked = payload.get("rerank", {}).get("results")
                top = (
                    list(dict.fromkeys(r["id"] for r in ranked))[:10]
                    if ranked is not None
                    else None
                )
                expected = case["expected_chunk_ids"]
                record.update(
                    status=200,
                    query_record_id=payload["query_record_id"],
                    retrieval_top10=metrics(expected, top, 10)
                    if top is not None
                    else None,
                    retrieval_top10_ids=top,
                    final_evidence=metrics(expected, final, 6),
                    final_evidence_ids=final,
                    publication_versions=payload["publication_versions"],
                    configuration_id=payload["configuration_id"],
                    application_configuration_id=payload.get(
                        "application_configuration_id"
                    ),
                    degraded=payload["degraded"],
                    evidence_status=payload["evidence_status"],
                    timings_ms=payload.get("timings_ms"),
                    model_usage=payload.get("model_usage"),
                    excluded=payload.get("excluded"),
                )
                try:
                    record["cost"] = client.request_cost(
                        payload["query_record_id"],
                        application_id=case.get("application_id"),
                    )
                except ApiError as error:
                    record["cost_unavailable_status"] = error.status
                record["search_latency_ms"] = round((time.monotonic() - started) * 1000)
                if answers:
                    answer_started = time.monotonic()
                    record["answer_attempted"] = True
                    text, answer_id, generation_cost = [], None, None
                    with client.answer(query) as events:
                        for event in events:
                            if event.name == "delta":
                                text.append(event.data.get("text", ""))
                            elif event.name == "done":
                                answer_id = event.data.get("answer_id")
                                generation_cost = event.data.get("query_record_id")
                            elif event.name == "error":
                                record["answer_error_code"] = event.data.get("code")
                    record.update(
                        answer_id=answer_id,
                        answer="".join(text),
                        answer_completed=answer_id is not None,
                        answer_latency_ms=round(
                            (time.monotonic() - answer_started) * 1000
                        ),
                    )
                    if generation_cost:
                        try:
                            record["answer_cost"] = client.request_cost(
                                generation_cost,
                                application_id=case.get("application_id"),
                            )
                        except ApiError as error:
                            record["answer_cost_unavailable_status"] = error.status
            except (ApiError, StreamError, httpx.HTTPError) as error:
                record.update(
                    status=error.status if isinstance(error, ApiError) else 503,
                    request_id=error.request_id
                    if isinstance(error, ApiError)
                    else None,
                    error_code=error.code
                    if isinstance(error, ApiError)
                    else "STREAM_INTERRUPTED"
                    if isinstance(error, StreamError)
                    else "TRANSPORT_ERROR",
                )
                if record.get("answer_attempted"):
                    record.update(
                        answer_completed=False,
                        answer="".join(text),
                        answer_latency_ms=round(
                            (time.monotonic() - answer_started) * 1000
                        ),
                    )
            record["latency_ms"] = round((time.monotonic() - started) * 1000)
            results.append(record)
            if progress:
                progress(record)
            if checkpoint:
                checkpoint(results)
    return {
        "schema_version": 2,
        "complete": True,
        "label": label,
        "dataset_version_id": dataset_version,
        "dataset_digest": digest,
        "evidence_mapping_digest": mapping_digest,
        "baseline_status": "HUMAN_REVIEWED_LABELS"
        if human_reviewed
        else "PROVISIONAL_UNREVIEWED_LABELS",
        "mode": mode,
        "answer_generation_enabled": answers,
        "retrieval_top10_stage": "authorized_rerank_before_evidence_limit_and_threshold",
        "final_evidence_limit": 6,
        "summary": summarize(results),
        "results": results,
        "limitations": [
            "Human answer correctness, grounding, citation accuracy and refusal ratings remain pending",
            "Debug-restricted identities have no Top10 metrics; final six is never relabeled Top10",
            "Changing source publications requires preserving label meaning; changed evidence mappings require renewed label review",
            "Generated answer text is private evaluation content; store reports outside Git unless all inputs are synthetic",
        ],
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("cases", nargs="?")
    parser.add_argument("--dataset-id")
    parser.add_argument("--version-id")
    parser.add_argument("--output", required=True)
    parser.add_argument("--base", default="http://localhost:8080")
    parser.add_argument(
        "--mode", choices=("hybrid", "semantic", "keyword"), default="hybrid"
    )
    parser.add_argument("--label", default="experiment")
    parser.add_argument("--answers", action="store_true")
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    reviewed = False
    if args.dataset_id and args.version_id:
        from careflow_sdk import Client

        with Client(args.base, os.environ["CAREFLOW_TOKEN"]) as client:
            dataset = client.evaluation_dataset(args.dataset_id)
            version = client.evaluation_dataset_version(
                args.dataset_id, args.version_id
            )
        cases = [
            dict(
                c,
                knowledge_base_ids=[dataset["kb_id"]],
                application_id=c["test_subject_id"]
                if c["test_subject_kind"] == "APP"
                else None,
            )
            for c in version["cases"]
        ]
        approvals = {
            r["case_id"] for r in version["reviews"] if r["decision"] == "APPROVED"
        }
        reviewed = all(c["id"] in approvals for c in cases)
    elif args.cases:
        text = Path(args.cases).read_text()
        source = (
            [json.loads(line) for line in text.splitlines() if line.strip()]
            if Path(args.cases).suffix == ".jsonl"
            else json.loads(text)
        )
        cases = source if isinstance(source, list) else source["cases"]
    else:
        parser.error("Provide a cases JSON file or both --dataset-id and --version-id")
    if not cases:
        raise SystemExit("A nonempty evaluation dataset is required")
    output = Path(args.output)
    initial = []
    if args.resume and output.exists():
        previous = json.loads(output.read_text())
        if (
            previous.get("resume_cases") != cases
            or previous.get("mode") != args.mode
            or previous.get("answer_generation_enabled") != args.answers
        ):
            raise ValueError(
                "Resume requires the identical cases, evidence mapping, mode and phases"
            )
        initial = previous["results"]

    def checkpoint(rows):
        temporary = output.with_suffix(output.suffix + ".tmp")
        temporary.write_text(
            json.dumps(
                {
                    "schema_version": 2,
                    "complete": False,
                    "resume_cases": cases,
                    "mode": args.mode,
                    "answer_generation_enabled": args.answers,
                    "results": rows,
                },
                ensure_ascii=False,
                indent=2,
            )
            + "\n"
        )
        temporary.replace(output)

    report = run(
        cases,
        args.base,
        os.environ["CAREFLOW_TOKEN"],
        mode=args.mode,
        label=args.label,
        answers=args.answers,
        dataset_version=args.version_id,
        human_reviewed=reviewed,
        initial_results=initial,
        checkpoint=checkpoint,
        progress=lambda row: print(
            f"case={row['case_id']} status={row['status']}", flush=True
        ),
    )
    report["resume_cases"] = cases
    Path(args.output).write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    )


if __name__ == "__main__":
    main()
