"""Per-page observer inside the isolated parser. Reports contain no document content."""

from contextlib import contextmanager
from contextvars import ContextVar
from uuid import uuid4

_observer = ContextVar("ocr_usage_observer", default=None)


@contextmanager
def observe(callback):
    token = _observer.set(callback)
    try:
        yield
    finally:
        _observer.reset(token)


def run(operation):
    observer = _observer.get()
    call_id = str(uuid4())
    if observer is not None:
        observer(call_id, "STARTED")
    try:
        result = operation()
    except Exception:
        if observer is not None:
            observer(call_id, "FAILED")
        raise
    if observer is not None:
        observer(call_id, "SUCCEEDED")
    return result
