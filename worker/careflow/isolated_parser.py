"""One bounded process per document; parent always reaps timed-out children."""

import multiprocessing
import os
import sys
import time

from careflow.models import ModelUnavailable
from careflow.parsing_limits import Limits, bounded
from careflow.parsing_types import InvalidFile


class ParseFailure(InvalidFile):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


def _limit_process():
    if sys.platform == "linux":
        import resource

        memory = bounded("PARSE_MEMORY_MIB", 2048) * 1024 * 1024
        resource.setrlimit(resource.RLIMIT_AS, (memory, memory))
        seconds = Limits.environment().parse_seconds
        resource.setrlimit(resource.RLIMIT_CPU, (seconds, seconds))


def _child(connection, data, filename, pdf_page_limit, chunking, embedding):
    try:
        if pdf_page_limit is not None:
            os.environ["PARSE_MAX_PDF_PAGES"] = str(
                min(Limits.environment().pdf_pages, pdf_page_limit)
            )
        _limit_process()
        from careflow.context_chunking import process_blocks
        from careflow.model_configuration import use_configuration
        from careflow.ocr_usage import observe
        from careflow.parsing import parse

        def report(call_id, state):
            connection.send(("OCR", (call_id, state)))
            if connection.recv() != "ACK":
                raise ParseFailure("OCR_USAGE_UNAVAILABLE")

        with use_configuration(embedding), observe(report):
            connection.send(("OK", process_blocks(parse(data, filename), chunking)))
    except MemoryError:
        connection.send(("PARSE_RESOURCE_LIMIT", None))
    except ModelUnavailable:
        connection.send(("MODEL_TOKENIZER_UNAVAILABLE", None))
    except InvalidFile:
        connection.send(("INVALID_FILE", None))
    except Exception:
        # Library errors must not leak document text, local paths or raw vendor output.
        connection.send(("PARSING_FAILED", None))
    finally:
        connection.close()


def parse_document(
    data: bytes,
    filename: str,
    cancelled=None,
    pdf_page_limit=None,
    chunking=None,
    embedding=None,
    record_ocr=None,
) -> dict:
    seconds = Limits.environment().parse_seconds
    context = multiprocessing.get_context("spawn")
    receiver, sender = context.Pipe(duplex=True)
    process = context.Process(
        target=_child,
        args=(sender, data, filename, pdf_page_limit, chunking, embedding),
        daemon=True,
    )
    try:
        process.start()
        sender.close()
        deadline = time.monotonic() + seconds
        while True:
            if cancelled is not None and cancelled():
                raise ParseFailure("LEASE_LOST")
            if time.monotonic() >= deadline:
                raise ParseFailure("PARSE_TIMEOUT")
            if not receiver.poll(min(1, max(0, deadline - time.monotonic()))):
                continue
            try:
                status, result = receiver.recv()
            except EOFError as exc:
                raise ParseFailure("PARSE_RESOURCE_LIMIT") from exc
            if status == "OCR":
                if record_ocr is not None:
                    record_ocr(*result)
                receiver.send("ACK")
                continue
            if status != "OK":
                raise ParseFailure(status)
            return result
    finally:
        sender.close()
        receiver.close()
        if process.pid is not None:
            if process.is_alive():
                process.terminate()
            process.join(timeout=5)
            if process.is_alive():
                process.kill()
                process.join(timeout=5)
            process.close()
