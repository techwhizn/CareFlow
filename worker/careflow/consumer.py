"""At-least-once task delivery with backend-owned fenced leases."""

import logging
import os
import threading
import time

import httpx
import pika

from careflow import models, parsing, retrieval
from careflow.isolated_parser import ParseFailure, parse_document
from careflow.protocol_v1 import IndexCompletion, ParseCompletion, TaskClaim

log = logging.getLogger(__name__)


def run_job(job_id):
    base = (
        os.environ.get("BACKEND_URL", "http://localhost:8080")
        + "/internal/v1/jobs/"
        + job_id
    )
    headers = {"X-Internal-Token": os.environ["INTERNAL_TOKEN"]}
    with httpx.Client(timeout=90) as client:
        claimed = client.post(base + "/claim", headers=headers)
        if claimed.status_code in (404, 409):
            return
        claimed.raise_for_status()
        job = TaskClaim.model_validate(claimed.json()).model_dump()
        headers["X-Lease-Token"] = job["lease_token"]
        stop = threading.Event()
        lease_lost = threading.Event()

        def heartbeat():
            while not stop.wait(20):
                try:
                    r = httpx.post(base + "/heartbeat", headers=headers, timeout=10)
                    r.raise_for_status()
                except Exception:
                    lease_lost.set()
                    return

        thread = threading.Thread(target=heartbeat, daemon=True)
        thread.start()

        def checkpoint(stage):
            if lease_lost.is_set():
                raise ParseFailure("LEASE_LOST")
            client.post(
                base + "/checkpoint", headers=headers, json={"stage": stage}
            ).raise_for_status()

        try:
            if job["kind"] == "PARSE":
                source = client.get(base + "/source", headers=headers)
                source.raise_for_status()
                checkpoint("SOURCE_READY")
                result = {
                    "chunks": parse_document(
                        source.content, job["filename"], cancelled=lease_lost.is_set
                    )
                }
                result = ParseCompletion.model_validate(result).model_dump()
                checkpoint("PARSED")
            else:
                chunks = client.get(base + "/chunks", headers=headers)
                chunks.raise_for_status()
                checkpoint("INDEXING")
                result = retrieval.index(
                    job["tenant_id"], job["version_id"], chunks.json()
                )
                result = IndexCompletion.model_validate(result).model_dump()
                checkpoint("INDEX_VERIFIED")
            if not lease_lost.is_set():
                client.post(
                    base + "/complete", headers=headers, json=result
                ).raise_for_status()
        except Exception as exc:
            if not lease_lost.is_set():
                # Never put file content, vendor responses or secrets into public job errors.
                if isinstance(exc, ParseFailure):
                    code = exc.code
                elif isinstance(exc, models.ModelUnavailable):
                    code = "MODEL_CONFIGURATION_REQUIRED"
                elif isinstance(exc, (parsing.InvalidFile, ValueError)):
                    code = "INVALID_FILE"
                else:
                    code = "PROCESSING_UNAVAILABLE"
                client.post(
                    base + "/failed",
                    headers=headers,
                    json={"code": code, "retryable": code == "PROCESSING_UNAVAILABLE"},
                ).raise_for_status()
        finally:
            stop.set()
            thread.join(timeout=15)


def main():
    logging.basicConfig(level=logging.INFO)
    while True:
        try:
            credentials = pika.PlainCredentials(
                os.environ.get("RABBITMQ_USER", "careflow"),
                os.environ["RABBITMQ_PASSWORD"],
            )
            connection = pika.BlockingConnection(
                pika.ConnectionParameters(
                    host=os.environ.get("RABBITMQ_HOST", "localhost"),
                    credentials=credentials,
                    heartbeat=0,
                    blocked_connection_timeout=30,
                )
            )
            channel = connection.channel()
            channel.queue_declare(queue="careflow.processing", durable=True)
            channel.basic_qos(prefetch_count=1)

            def receive(ch, method, properties, body):
                try:
                    run_job(body.decode())
                    ch.basic_ack(method.delivery_tag)
                except Exception:
                    log.error(
                        "Job delivery failed; backend lease recovery will reconcile"
                    )
                    ch.basic_nack(method.delivery_tag, requeue=True)
                    time.sleep(2)

            channel.basic_consume(
                queue="careflow.processing", on_message_callback=receive
            )
            channel.start_consuming()
        except Exception:
            log.error("Queue unavailable; reconnecting")
            time.sleep(5)


if __name__ == "__main__":
    main()
