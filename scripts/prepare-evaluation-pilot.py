"""Import synthetic source documents and bind a candidate test set to real evidence.

Explicit --apply creates and publishes knowledge documents, never software releases.
Credentials stay in CAREFLOW_TOKEN; resumable state contains only synthetic resource IDs.
"""

import argparse
import json
import os
import time
import uuid
from pathlib import Path

import httpx


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="http://localhost:8080")
    parser.add_argument("--template-kb", required=True)
    parser.add_argument("--state", required=True)
    parser.add_argument(
        "--manifest", default="examples/evaluation/pilot/candidate.json"
    )
    parser.add_argument("--apply", action="store_true")
    parser.add_argument(
        "--retry-failed-index",
        action="store_true",
        help="Create a new index task for an explicitly retried failed synthetic import",
    )
    args = parser.parse_args()
    manifest_path = Path(args.manifest).resolve()
    manifest = json.loads(manifest_path.read_text())
    if not args.apply:
        print(
            f"Plan: {len(manifest['documents'])} synthetic documents, {len(manifest['cases'])} unreviewed cases"
        )
        return
    state_path = Path(args.state)
    state = (
        json.loads(state_path.read_text()) if state_path.exists() else {"documents": {}}
    )

    def save():
        state_path.parent.mkdir(parents=True, exist_ok=True)
        temporary = state_path.with_suffix(".tmp")
        temporary.write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n")
        temporary.replace(state_path)

    with httpx.Client(
        base_url=args.base.rstrip("/") + "/api/v1/",
        headers={"Authorization": "Bearer " + os.environ["CAREFLOW_TOKEN"]},
        timeout=120,
        trust_env=False,
    ) as client:

        def call(method, path, **kwargs):
            headers = {"Idempotency-Key": str(uuid.uuid4())} if method == "POST" else {}
            response = client.request(method, path, headers=headers, **kwargs)
            if not response.is_success:
                raise RuntimeError(
                    f"Public API failed: {method} {path} HTTP {response.status_code}"
                )
            return response.json() if response.content else None

        def wait(job):
            for _ in range(180):
                value = call("GET", "jobs/" + job)
                if value["state"] == "DONE":
                    return
                if value["state"] in ("FAILED", "CANCELLED"):
                    raise RuntimeError(
                        f"Synthetic job {job} failed: {value.get('error_code')}"
                    )
                time.sleep(1)
            raise TimeoutError("Task did not complete within 180 seconds")

        identity = call("GET", "me")
        if state.get("tenant_id", identity["tenant_id"]) != identity["tenant_id"]:
            raise ValueError("State belongs to another tenant")
        state["tenant_id"] = identity["tenant_id"]
        if "knowledge_base_id" not in state:
            state["knowledge_base_id"] = call(
                "POST",
                "knowledge-bases",
                json={
                    "name": manifest["name"],
                    "description": "虚构试点候选资料，待人工评审，不代表真实产品政策",
                },
            )["id"]
            save()
        kb = state["knowledge_base_id"]
        if "configuration_id" not in state:
            template = call(
                "GET",
                "knowledge-bases/"
                + str(uuid.UUID(args.template_kb))
                + "/configurations",
            )
            definition = next(
                v["definition"]
                for v in template["versions"]
                if v["id"] == template["published_configuration"]
            )
            definition["name"] = "试点评估冻结配置"
            cfg = call("POST", f"knowledge-bases/{kb}/configurations", json=definition)
            revision = call("GET", f"knowledge-bases/{kb}/configurations")["revision"]
            call(
                "POST",
                f"knowledge-bases/{kb}/configuration-publications",
                json={
                    "configuration_id": cfg["id"],
                    "revision": revision,
                    "reason": "合成试点固定模型与切片配置",
                },
            )
            state["configuration_id"] = cfg["id"]
            save()
        for source in manifest["documents"]:
            key = source["key"]
            row = state["documents"].setdefault(key, {})
            if "job_id" not in row:
                path = (manifest_path.parent / source["path"]).resolve()
                if not path.is_relative_to(manifest_path.parent):
                    raise ValueError("Source path leaves manifest directory")
                with path.open("rb") as file:
                    row.update(
                        call(
                            "POST",
                            f"knowledge-bases/{kb}/documents",
                            files={"file": (path.name, file, "text/markdown")},
                        )
                    )
                save()
            if not row.get("published"):
                wait(row["job_id"])
                if row.get("index_job_id") and args.retry_failed_index:
                    previous = call("GET", "jobs/" + row["index_job_id"])
                    if previous["state"] in ("FAILED", "CANCELLED"):
                        row.setdefault("failed_index_job_ids", []).append(
                            row.pop("index_job_id")
                        )
                        save()
                if "index_job_id" not in row:
                    row["index_job_id"] = call(
                        "POST", f"document-versions/{row['version_id']}/index"
                    )["job_id"]
                    save()
                wait(row["index_job_id"])
                documents = call("GET", f"knowledge-bases/{kb}/documents")
                document = next(d for d in documents if d["id"] == row["document_id"])
                versions = call("GET", f"documents/{row['document_id']}/versions")
                version = next(v for v in versions if v["id"] == row["version_id"])
                if document.get("published_version") != row["version_id"]:
                    call(
                        "POST",
                        f"documents/{row['document_id']}/publications",
                        json={
                            "version_id": row["version_id"],
                            "revision": document["revision"],
                            "version_revision": version["revision"],
                        },
                    )
                row["expected_chunk_ids"] = [
                    c["id"]
                    for c in call(
                        "GET", f"document-versions/{row['version_id']}/chunks"
                    )
                    if c["enabled"]
                ]
                row["published"] = True
                save()
            print(f"Source ready: {key}", flush=True)
        cases = []
        for source in manifest["cases"]:
            case = {k: v for k, v in source.items() if k != "expected_source_keys"}
            case["expected_chunk_ids"] = list(
                dict.fromkeys(
                    c
                    for key in source["expected_source_keys"]
                    for c in state["documents"][key]["expected_chunk_ids"]
                )
            )
            case.update(
                test_subject_kind="MEMBER", test_subject_id=identity["subject_id"]
            )
            cases.append(case)
        if "dataset_id" not in state:
            state["dataset_id"] = call(
                "POST",
                "evaluation-datasets",
                json={"knowledge_base_id": kb, "name": manifest["name"]},
            )["id"]
            save()
        if "dataset_version_id" not in state:
            dataset = call("GET", "evaluation-datasets/" + state["dataset_id"])
            result = call(
                "POST",
                f"evaluation-datasets/{state['dataset_id']}/versions",
                json={"revision": dataset["revision"], "cases": cases},
            )
            state["dataset_version_id"] = result["id"]
            state["review_status"] = "PENDING_HUMAN_REVIEW"
            save()
        print(
            "Candidate dataset prepared; no human approvals have been recorded",
            flush=True,
        )


if __name__ == "__main__":
    main()
