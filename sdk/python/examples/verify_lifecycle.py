"""Explicit live SDK check against a configured synthetic fixture knowledge base."""

import argparse
import asyncio
import json
import os
import platform
import tempfile
import time
import uuid
from pathlib import Path

from careflow_sdk import AsyncClient, Client, Query


def wait_for_job(client, job_id):
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        job = client.job(job_id)
        if job["state"] == "DONE":
            return
        if job["state"] in {"FAILED", "CANCELLED"}:
            raise RuntimeError(f"Job failed: {job.get('error_code')}")
        time.sleep(1)
    raise TimeoutError("Job did not complete within three minutes")


def ingest(client, kb):
    uploaded = client.upload(kb, Path(os.environ["CAREFLOW_FILE"]))
    wait_for_job(client, uploaded["job_id"])
    wait_for_job(client, client.index(uploaded["version_id"])["job_id"])
    document = next(
        row for row in client.documents(kb) if row["id"] == uploaded["document_id"]
    )
    version = next(
        row
        for row in client.document_versions(document["id"])
        if row["id"] == uploaded["version_id"]
    )
    client.publish(
        document["id"], version["id"], document["revision"], version["revision"]
    )
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "sdk-cancel.md"
        path.write_text("# Synthetic cancellation fixture\n" + str(uuid.uuid4()))
        pending = client.upload(kb, path)
        client.cancel_job(pending["job_id"])
        if client.job(pending["job_id"])["state"] != "CANCELLED":
            raise RuntimeError("Job was not cancelled")
    return {
        "document_id": document["id"],
        "version_id": version["id"],
        "cancelled_job_id": pending["job_id"],
        "upload_parse_index_publish": "passed",
    }


async def answer(base, token, kb):
    query = Query(
        os.environ["CAREFLOW_QUERY"],
        knowledge_base_ids=(kb,),
        mode="hybrid",
        debug=True,
    )
    async with AsyncClient(base, token) as client:
        result = await client.search(query)
        if not result["evidence"]:
            raise RuntimeError("No evidence")
        names = set()
        characters = 0
        async with client.answer(query) as events:
            async for event in events:
                names.add(event.name)
                if event.name == "delta":
                    characters += len(event.data.get("text", ""))
        if not {"delta", "citations", "done"} <= names:
            raise RuntimeError("Answer did not complete with citations")
        request_id = None
        stopped = False
        async with client.answer(query) as events:
            async for event in events:
                if event.name == "start":
                    request_id = event.data["request_id"]
                if event.name == "delta":
                    stopped = True
                    break
        if not stopped or request_id is None:
            raise RuntimeError("No partial stream to cancel")
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            log = await client.request_log(request_id)
            if log["outcome"] != "RUNNING":
                break
            await asyncio.sleep(1)
        if log["outcome"] != "CANCELLED":
            raise RuntimeError("Server cancellation did not settle: " + log["outcome"])
        return {
            "events": sorted(names),
            "answer_characters": characters,
            "stream_closed_after_first_delta": True,
            "cancelled_request_id": request_id,
            "server_status": log["outcome"],
        }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("ingest", "answer"))
    args = parser.parse_args()
    base, token, kb = (
        os.environ[name]
        for name in ("CAREFLOW_URL", "CAREFLOW_TOKEN", "CAREFLOW_KB_ID")
    )
    if args.operation == "ingest":
        with Client(base, token) as client:
            result = ingest(client, kb)
    else:
        result = asyncio.run(answer(base, token, kb))
    print(
        json.dumps(
            {
                "language": "python",
                "python_version": platform.python_version(),
                "knowledge_base_id": kb,
                **result,
            }
        )
    )


if __name__ == "__main__":
    main()
