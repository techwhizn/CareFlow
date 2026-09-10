"""Deterministic retrieval evaluation against the real public API; no model self-grading."""

import argparse
import json
import os
import time
import uuid
from pathlib import Path

import httpx


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


def run(cases, base, token):
    results = []
    with httpx.Client(
        base_url=base.rstrip("/"),
        timeout=120,
        headers={"Authorization": "Bearer " + token},
    ) as client:
        for case in cases:
            started = time.monotonic()
            response = client.post(
                "/api/v1/retrieval/search",
                headers={"Idempotency-Key": str(uuid.uuid4())},
                json={
                    "query": case["question"],
                    "knowledge_base_ids": case.get("knowledge_base_ids", []),
                    "application_id": case.get("application_id"),
                    "mode": "hybrid",
                    "limit": 6,
                    "debug": True,
                },
            )
            record = {
                "case_id": case["id"],
                "latency_ms": round((time.monotonic() - started) * 1000),
                "status": response.status_code,
            }
            if response.is_success:
                payload = response.json()
                ids = [e["id"] for e in payload["evidence"]]
                record.update(metrics(case["expected_chunk_ids"], ids, k=6))
                record["trace_id"] = payload["trace_id"]
                record["publication_versions"] = payload["publication_versions"]
                record["degraded"] = payload["degraded"]
            results.append(record)
    eligible = [r for r in results if r.get("recall") is not None]
    latencies = sorted(r["latency_ms"] for r in results)
    return {
        "metric_k": 6,
        "cases": len(results),
        "failure_rate": sum(r["status"] != 200 for r in results) / len(results)
        if results
        else 0,
        "recall_at_6": sum(r["recall"] for r in eligible) / len(eligible)
        if eligible
        else None,
        "hit_at_6": sum(r["hit"] for r in eligible) / len(eligible)
        if eligible
        else None,
        "mrr_at_6": sum(r["mrr"] for r in eligible) / len(eligible)
        if eligible
        else None,
        "p95_ms": latencies[min(len(latencies) - 1, int(len(latencies) * 0.95))]
        if latencies
        else None,
        "results": results,
        "limitations": [
            "Final evidence limit is six, so this report cannot establish PRD Hit@10",
            "Answer correctness and grounding require independent human review",
            "Record machine, provider locations and dataset identity alongside this report",
        ],
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("cases")
    parser.add_argument("--output", required=True)
    parser.add_argument("--base", default="http://localhost:8080")
    args = parser.parse_args()
    cases = [
        json.loads(line)
        for line in Path(args.cases).read_text().splitlines()
        if line.strip()
    ]
    if not cases:
        raise SystemExit("A nonempty, manually labeled dataset is required")
    report = run(cases, args.base, os.environ["CAREFLOW_TOKEN"])
    Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2))
