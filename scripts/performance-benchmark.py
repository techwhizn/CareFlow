"""Measure a prepared synthetic corpus via public API; never store response text.

Credentials: CAREFLOW_BENCHMARK_ACTORS points to a private JSON list containing
token and kb_id for each tenant. This probe creates metering/query records and,
with --answers, calls the configured paid model and cancels after its first delta.
"""

import argparse
import asyncio
from collections import Counter
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import time
import uuid

import httpx


def percentile(values, fraction):
    if not values:
        return None
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)]


def summary(rows, elapsed):
    successful = [row for row in rows if row["outcome"] == "OK"]
    result = {
        "offered": len(rows),
        "successful": len(successful),
        "outcomes": dict(Counter(row["outcome"] for row in rows)),
        "failure_fraction": 1 - len(successful) / len(rows),
        "elapsed_seconds": elapsed,
        "successful_qps": len(successful) / elapsed,
    }
    for field in [
        "elapsed_ms",
        "first_token_ms",
        "pure_retrieval_ms",
        "embedding_ms",
        "rerank_ms",
    ]:
        values = [row[field] for row in successful if field in row]
        result[field] = {
            "samples": len(values),
            "p50": percentile(values, 0.5),
            "p95": percentile(values, 0.95),
            "maximum": max(values, default=None),
        }
    return result


async def request(client, actor, tenant_index, sequence, operation):
    started = time.perf_counter()
    row = {"tenant_index": tenant_index, "sequence": sequence}
    headers = {
        "Authorization": "Bearer " + actor["token"],
        "Idempotency-Key": str(uuid.uuid4()),
    }
    query = {
        "query": f"How many seconds for CF-P{tenant_index + 1:02d}-{sequence % 10000 + 1:05d}?",
        "knowledge_base_ids": [actor["kb_id"]],
        "mode": "hybrid",
        "limit": 6,
        "debug": True,
    }
    try:
        if operation == "answer":
            async with client.stream(
                "POST", "/api/v1/answers", headers=headers, json=query
            ) as response:
                row["http_status"] = response.status_code
                if response.status_code != 200:
                    row["outcome"] = f"HTTP_{response.status_code}"
                else:
                    event = ""
                    row["outcome"] = "NO_ANSWER_TOKEN"
                    async for line in response.aiter_lines():
                        if line.startswith("event:"):
                            event = line[6:].strip()
                        elif line.startswith("data:") and event == "delta":
                            if not json.loads(line[5:]).get("text"):
                                continue
                            row.update(
                                outcome="OK",
                                first_token_ms=(time.perf_counter() - started) * 1000,
                            )
                            break
                        elif line.startswith("data:") and event == "error":
                            row["outcome"] = "SSE_ERROR"
                            break
                        elif not line:
                            event = ""
        else:
            response = await (
                client.get("/api/v1/knowledge-bases", headers=headers)
                if operation == "api"
                else client.post(
                    "/api/v1/retrieval/search", headers=headers, json=query
                )
            )
            row["http_status"] = response.status_code
            row["outcome"] = (
                "OK" if response.status_code == 200 else f"HTTP_{response.status_code}"
            )
            if operation == "search" and response.status_code == 200:
                body = response.json()
                row["evidence_count"] = len(body["evidence"])
                row["degraded"] = body["degraded"]
                if body["degraded"]:
                    row["outcome"] = "DEGRADED"
                if not body["evidence"]:
                    row["outcome"] = "NO_EVIDENCE"
                groups = body.get("recall_timings_ms", [])
                if groups and all(
                    "pure_retrieval" in group and "embedding" in group
                    for group in groups
                ):
                    row["pure_retrieval_ms"] = sum(
                        group["pure_retrieval"] for group in groups
                    )
                    row["embedding_ms"] = sum(group["embedding"] for group in groups)
                if "rerank" in body.get("timings_ms", {}):
                    row["rerank_ms"] = body["timings_ms"]["rerank"]
    except httpx.TimeoutException:
        row["outcome"] = "TIMEOUT"
    except httpx.HTTPError:
        row["outcome"] = "NETWORK_ERROR"
    except (ValueError, KeyError, TypeError):
        row["outcome"] = "INVALID_RESPONSE"
    row["elapsed_ms"] = (time.perf_counter() - started) * 1000
    return row


async def run_stage(client, actors, operation, count, qps, concurrency):
    started = time.perf_counter()
    pending = set()
    rows = []
    for sequence in range(count):
        await asyncio.sleep(max(0, started + sequence / qps - time.perf_counter()))
        finished = {task for task in pending if task.done()}
        rows.extend(task.result() for task in finished)
        pending -= finished
        tenant_index = sequence % len(actors)
        if len(pending) >= concurrency:
            rows.append(
                {
                    "sequence": sequence,
                    "tenant_index": tenant_index,
                    "outcome": "LOAD_GENERATOR_CAPACITY",
                }
            )
        else:
            pending.add(
                asyncio.create_task(
                    request(
                        client, actors[tenant_index], tenant_index, sequence, operation
                    )
                )
            )
    rows.extend(await asyncio.gather(*pending))
    elapsed = time.perf_counter() - started
    return {
        "operation": operation,
        "offered_qps": qps,
        "concurrency_limit": concurrency,
        "summary": summary(rows, elapsed),
        "rows": sorted(rows, key=lambda row: row["sequence"]),
    }


async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True)
    parser.add_argument("--count", type=int, default=100)
    parser.add_argument("--qps", type=float, default=5)
    parser.add_argument("--concurrency", type=int, default=10)
    parser.add_argument("--answers", action="store_true")
    args = parser.parse_args()
    if not (
        10 <= args.count <= 10000
        and 0 < args.qps <= 100
        and 1 <= args.concurrency <= 100
    ):
        parser.error("count 10..10000, qps (0,100], concurrency 1..100 required")
    actors = json.loads(Path(os.environ["CAREFLOW_BENCHMARK_ACTORS"]).read_text())
    if len(actors) < 10 or len({actor["kb_id"] for actor in actors}) != len(actors):
        parser.error(
            "At least ten distinct prepared tenant knowledge bases are required"
        )
    output = Path(args.output)
    if output.exists():
        parser.error("Use a new report path; previous experiments are immutable")
    report = {
        "started_at": datetime.now(timezone.utc).isoformat(),
        "tenant_count": len(actors),
        "answer_probe": "cancel_after_first_delta" if args.answers else "not_run",
        "cache_scope": "First-query pass, then warm load; no OS cache eviction or model reload",
        "stages": [],
        "complete": False,
    }
    output.write_text(json.dumps(report, indent=2))
    async with httpx.AsyncClient(
        base_url=os.environ["CAREFLOW_URL"],
        timeout=120,
        trust_env=False,
        limits=httpx.Limits(max_connections=args.concurrency),
    ) as client:
        for operation in ["api", "search"] + (["answer"] if args.answers else []):
            rows = []
            started = time.perf_counter()
            for index, actor in enumerate(actors):
                rows.append(await request(client, actor, index, index, operation))
            report["stages"].append(
                {
                    "operation": operation,
                    "phase": "first_query_serial",
                    "summary": summary(rows, time.perf_counter() - started),
                    "rows": rows,
                }
            )
            output.write_text(json.dumps(report, indent=2))
            stage = await run_stage(
                client, actors, operation, args.count, args.qps, args.concurrency
            )
            stage["phase"] = "warm_load"
            report["stages"].append(stage)
            output.write_text(json.dumps(report, indent=2))
            print(
                json.dumps({"operation": operation, "summary": stage["summary"]}),
                flush=True,
            )
    report.update(complete=True, finished_at=datetime.now(timezone.utc).isoformat())
    output.write_text(json.dumps(report, indent=2))


if __name__ == "__main__":
    asyncio.run(main())
