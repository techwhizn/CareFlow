"""One bounded process per document; parent always reaps timed-out children."""

import multiprocessing
import sys

from careflow.parsing_limits import Limits, bounded
from careflow.parsing_types import InvalidFile


class ParseFailure(InvalidFile):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


def _child(connection, data, filename):
    try:
        if sys.platform == "linux":
            import resource

            memory = bounded("PARSE_MEMORY_MIB", 2048) * 1024 * 1024
            resource.setrlimit(resource.RLIMIT_AS, (memory, memory))
            seconds = Limits.environment().parse_seconds
            resource.setrlimit(resource.RLIMIT_CPU, (seconds, seconds))
        from careflow.parsing import chunk, parse

        connection.send(("OK", chunk(parse(data, filename))))
    except MemoryError:
        connection.send(("PARSE_RESOURCE_LIMIT", None))
    except InvalidFile:
        connection.send(("INVALID_FILE", None))
    except Exception:
        # Library errors must not leak document text, local paths or raw vendor output.
        connection.send(("PARSING_FAILED", None))
    finally:
        connection.close()


def parse_document(data: bytes, filename: str) -> list[dict]:
    seconds = Limits.environment().parse_seconds
    context = multiprocessing.get_context("spawn")
    receiver, sender = context.Pipe(duplex=False)
    process = context.Process(target=_child, args=(sender, data, filename), daemon=True)
    try:
        process.start()
        sender.close()
        if not receiver.poll(seconds):
            raise ParseFailure("PARSE_TIMEOUT")
        try:
            status, result = receiver.recv()
        except EOFError as exc:
            raise ParseFailure("PARSE_RESOURCE_LIMIT") from exc
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
