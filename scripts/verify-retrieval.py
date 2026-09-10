"""Real annotated feature acceptance; creates a clearly marked synthetic knowledge base."""

import argparse
import json
import os
from pathlib import Path
import time
import uuid

import httpx


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source-kb",
        required=True,
        help="Existing KB with a published real-model configuration",
    )
    parser.add_argument("--output", default="docs/reports/v1-24-annotated-real.json")
    args = parser.parse_args()
    token = os.environ["CAREFLOW_TOKEN"]
    data = json.loads(Path("examples/retrieval/annotated.json").read_text())
    report = {
        "synthetic_data": True,
        "real_dependencies": True,
        "started_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "cases": [],
        "complete": False,
    }
    output = Path(args.output)
    with httpx.Client(
        base_url=os.getenv("CAREFLOW_URL", "http://localhost:8080") + "/api/v1",
        headers={"Authorization": "Bearer " + token},
        timeout=120,
        trust_env=False,
    ) as client:

        def call(method, path, **kwargs):
            response = client.request(
                method, path, headers={"Idempotency-Key": str(uuid.uuid4())}, **kwargs
            )
            if response.status_code != 200:
                raise RuntimeError(f"{method} {path}: HTTP {response.status_code}")
            return response.json() if response.content else None

        def wait(job):
            deadline = time.monotonic() + 300
            while time.monotonic() < deadline:
                state = call("GET", "/jobs/" + job)
                if state["state"] == "DONE":
                    return
                if state["state"] in {"FAILED", "CANCELLED"}:
                    raise RuntimeError(
                        "Synthetic task failed: " + str(state.get("error_code"))
                    )
                time.sleep(2)
            raise TimeoutError("Synthetic task timed out")

        try:
            config = call(
                "GET", f"/knowledge-bases/{uuid.UUID(args.source_kb)}/configurations"
            )["versions"][0]["definition"]
            config["name"] = "合成标注检索验收"
            config["chunking"].update(
                layout="standard", target=300, maximum=500, overlap=0
            )
            kb = call(
                "POST",
                "/knowledge-bases",
                json={
                    "name": "[验收]同义问题与错误码检索",
                    "description": "源资料预标注的合成特性测试",
                },
            )["id"]
            cfg = call("POST", f"/knowledge-bases/{kb}/configurations", json=config)[
                "id"
            ]
            call(
                "POST",
                f"/knowledge-bases/{kb}/configuration-publications",
                json={"configuration_id": cfg, "revision": 0, "reason": "合成检索验收"},
            )
            documents = {}
            for doc in data["documents"]:
                uploaded = call(
                    "POST",
                    f"/knowledge-bases/{kb}/documents",
                    files={
                        "file": (doc["id"] + ".txt", doc["text"].encode(), "text/plain")
                    },
                )
                wait(uploaded["job_id"])
                wait(
                    call("POST", f"/document-versions/{uploaded['version_id']}/index")[
                        "job_id"
                    ]
                )
                call(
                    "PUT",
                    f"/documents/{uploaded['document_id']}/metadata",
                    json={
                        "title": doc["id"],
                        "source": "synthetic acceptance",
                        "language": "zh",
                        "tags": ["acceptance"],
                        "product_models": [doc["model"]],
                        "revision": 0,
                    },
                )
                call(
                    "POST",
                    f"/documents/{uploaded['document_id']}/publications",
                    json={
                        "version_id": uploaded["version_id"],
                        "revision": 1,
                        "version_revision": 0,
                    },
                )
                documents[doc["id"]] = uploaded
            report["fixture"] = {
                "knowledge_base_id": kb,
                "configuration_id": cfg,
                "documents": documents,
            }
            for case in data["cases"]:
                for mode in case["modes"]:
                    started = time.monotonic()
                    result = call(
                        "POST",
                        "/retrieval/search",
                        json={
                            "query": case["query"],
                            "mode": mode,
                            "knowledge_base_ids": [kb],
                            "limit": 6,
                            "debug": True,
                        },
                    )
                    expected = documents[case["expected_document"]]["document_id"]
                    matches = [
                        index + 1
                        for index, item in enumerate(result["evidence"])
                        if item["document_id"] == expected
                    ]
                    passed = (
                        bool(matches) and matches[0] <= 3 and not result["degraded"]
                    )
                    lanes = result["recall"]
                    if mode in {"semantic", "hybrid"}:
                        passed = passed and bool(lanes["dense"])
                    if mode in {"keyword", "hybrid"}:
                        passed = passed and bool(lanes["bm25"])
                    passed = (
                        passed
                        and bool(lanes["fused"])
                        and bool(result["rerank"]["results"])
                    )
                    report["cases"].append(
                        {
                            "case": case["id"],
                            "mode": mode,
                            "expected_rank": matches[0] if matches else None,
                            "passed": passed,
                            "elapsed_ms": round((time.monotonic() - started) * 1000),
                            "recall": lanes,
                            "rerank": result["rerank"],
                        }
                    )
                    output.write_text(
                        json.dumps(report, ensure_ascii=False, indent=2) + "\n"
                    )
                    print(case["id"], mode, "PASS" if passed else "FAIL", flush=True)
            for model, expected_count in [("CF-100", 2), ("unknown-model", 0)]:
                result = call(
                    "POST",
                    "/retrieval/search",
                    json={
                        "query": "CF-100 网络故障 E404",
                        "mode": "hybrid",
                        "knowledge_base_ids": [kb],
                        "limit": 6,
                        "debug": True,
                        "filters": [
                            {
                                "field": "product_models",
                                "operator": "contains",
                                "value": model,
                            }
                        ],
                    },
                )
                assert len(result["publication_versions"]) == expected_count
                assert all(
                    e["document_id"]
                    in {documents[x]["document_id"] for x in ["offline", "reset"]}
                    for e in result["evidence"]
                )
                if expected_count == 0:
                    assert not result["evidence"] and not any(result["recall"].values())
            report["filter_match_and_empty_scope_verified"] = True
            report["complete"] = all(case["passed"] for case in report["cases"])
            if not report["complete"]:
                raise AssertionError(
                    "Annotated retrieval did not meet feature acceptance"
                )
        finally:
            output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
