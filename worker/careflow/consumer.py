"""At-least-once task delivery with backend-owned fenced leases."""

import logging
import os
import threading
import time

import httpx
import pika

from careflow import models, parsing, retrieval
from careflow.isolated_parser import ParseFailure, parse_document
from careflow.model_configuration import use_configuration
from careflow.protocol_v1 import IndexCompletion, ParseCompletion, TaskClaim

log = logging.getLogger(__name__)


def run_job(job_id):
    base = (
        os.environ.get("BACKEND_URL", "http://localhost:8080")
        + "/internal/v1/jobs/"
        + job_id
    )
    headers = {
        "X-Internal-Token": os.environ["INTERNAL_TOKEN"],
        "X-Correlation-ID": job_id,
    }
    with httpx.Client(timeout=90) as client:
        claimed = client.post(base + "/claim", headers=headers)
        if claimed.status_code in (404, 409):
            return
        if claimed.status_code in (403, 429) and claimed.json().get("code") in (
            "ENTITLEMENT_INACTIVE",
            "QUOTA_EXCEEDED",
        ):
            # Java retains QUEUED and retries after the notification reservation expires.
            # Acknowledge this delivery so a paused tenant cannot block the shared queue.
            return
        claimed.raise_for_status()
        claim = TaskClaim.model_validate(claimed.json())
        job = claim.model_dump(exclude={"configuration"})
        log.info("job_id=%s state=CLAIMED kind=%s", job_id, job["kind"])
        configuration = claim.configuration
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

        def record_call(call_id, state, input_count, tokens):
            client.put(
                base + "/model-calls/" + call_id,
                headers=headers,
                json={"state": state, "input_count": input_count, "tokens": tokens},
            ).raise_for_status()

        def record_ocr(call_id, state):
            client.put(
                base + "/ocr-pages/" + call_id,
                headers=headers,
                json={"state": state},
            ).raise_for_status()

        try:
            if job["kind"] == "PARSE":
                source = client.get(base + "/source", headers=headers)
                source.raise_for_status()
                checkpoint("SOURCE_READY")
                result = parse_document(
                    source.content,
                    job["filename"],
                    cancelled=lease_lost.is_set,
                    record_ocr=record_ocr,
                    pdf_page_limit=min(
                        job["pdf_page_limit"], configuration.parsing.pdf_page_limit
                    )
                    if configuration
                    else job["pdf_page_limit"],
                    chunking=configuration.chunking.model_dump()
                    if configuration
                    else None,
                    embedding=configuration.embedding if configuration else None,
                )
                result = ParseCompletion.model_validate(result).model_dump()
                checkpoint("PARSED")
            else:
                chunks = client.get(base + "/chunks", headers=headers)
                chunks.raise_for_status()
                checkpoint("INDEXING")
                with use_configuration(
                    configuration.embedding if configuration else None
                ):
                    result = retrieval.index(
                        job["tenant_id"],
                        job["version_id"],
                        chunks.json(),
                        chunking=configuration.chunking if configuration else None,
                        record_call=record_call,
                        generation_id=job["generation_id"],
                        before_write=lambda: checkpoint("INDEXING"),
                    )
                result = IndexCompletion.model_validate(result).model_dump()
                checkpoint("INDEX_VERIFIED")
            if not lease_lost.is_set():
                client.post(
                    base + "/complete", headers=headers, json=result
                ).raise_for_status()
                log.info("job_id=%s state=DONE", job_id)
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
                log.warning("job_id=%s state=FAILED error_code=%s", job_id, code)
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
