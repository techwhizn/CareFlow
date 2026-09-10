"""Run actual upload → parse → index → publish → hybrid search. No mocks permitted."""

import argparse
import json
import os
import time
import uuid
from pathlib import Path
import httpx

parser = argparse.ArgumentParser()
parser.add_argument("--base", default="http://localhost:8080")
parser.add_argument("--output", default="docs/reports/real-smoke.json")
args = parser.parse_args()
headers = {"Authorization": "Bearer " + os.environ["CAREFLOW_TOKEN"]}
report = {
    "started_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    "mode": "real-dependencies",
    "steps": [],
    "complete": False,
}


def call(client, method, path, **kwargs):
    r = client.request(
        method,
        "/api/v1" + path,
        headers={"Idempotency-Key": str(uuid.uuid4())},
        **kwargs,
    )
    r.raise_for_status()
    return r.json() if r.content else None


def wait_job(client, job_id):
    end = time.monotonic() + 900
    while time.monotonic() < end:
        job = call(client, "GET", "/jobs/" + job_id)
        if job["state"] == "DONE":
            return job
        if job["state"] in ["FAILED", "CANCELLED"]:
            raise RuntimeError("Real processing failed: " + str(job.get("error_code")))
        time.sleep(2)
    raise TimeoutError("Job did not finish within 15 minutes")


try:
    with httpx.Client(base_url=args.base, headers=headers, timeout=120) as client:
        kb = call(
            client,
            "POST",
            "/knowledge-bases",
            json={
                "name": "真实链路验收 " + str(uuid.uuid4())[:8],
                "description": "自动生成的真实集成验证知识库；使用合成手册",
            },
        )
        report["knowledge_base_id"] = kb["id"]
        data = Path("examples/product-guide.md").read_bytes()
        upload = call(
            client,
            "POST",
            "/knowledge-bases/" + kb["id"] + "/documents",
            files={"file": ("product-guide.md", data, "text/markdown")},
        )
        report["steps"].append({"parse": wait_job(client, upload["job_id"])})
        index = call(
            client, "POST", "/document-versions/" + upload["version_id"] + "/index"
        )
        report["steps"].append({"index": wait_job(client, index["job_id"])})
        call(
            client,
            "POST",
            "/documents/" + upload["document_id"] + "/publications",
            json={
                "version_id": upload["version_id"],
                "revision": 0,
                "version_revision": 0,
            },
        )
        result = call(
            client,
            "POST",
            "/retrieval/search",
            json={
                "query": "CF-100 报 E404 怎么处理？",
                "knowledge_base_ids": [kb["id"]],
                "mode": "hybrid",
                "limit": 6,
                "debug": True,
            },
        )
        if not result["evidence"] or result["degraded"]:
            raise RuntimeError("Search returned empty or degraded results")
        if (
            not result.get("recall", {}).get("dense")
            or not result.get("recall", {}).get("bm25")
            or not result.get("rerank", {}).get("results")
        ):
            raise RuntimeError("Missing real dense/BM25/model rerank evidence")
        report["steps"].append({"retrieval": result})
        question = {
            "query": "CF-100 报 E404 怎么处理？",
            "knowledge_base_ids": [kb["id"]],
            "mode": "hybrid",
            "limit": 6,
            "debug": False,
        }
        with client.stream(
            "POST",
            "/api/v1/answers",
            headers={"Idempotency-Key": str(uuid.uuid4())},
            json=question,
        ) as response:
            response.raise_for_status()
            events = list(response.iter_lines())
            if "event:done" not in events or any(
                "event:error" in line for line in events
            ):
                raise RuntimeError("Generation stream did not complete")
        report["steps"].append({"generation_events": events})
        report["complete"] = True
except Exception as exc:
    report["error_type"] = type(exc).__name__
    report["error"] = str(exc)[:500]
finally:
    Path(args.output).write_text(
        json.dumps(report, ensure_ascii=False, indent=2, default=str)
    )
if not report["complete"]:
    raise SystemExit("Real integration FAILED; inspect " + args.output)
print("Real integration PASSED; " + args.output)
