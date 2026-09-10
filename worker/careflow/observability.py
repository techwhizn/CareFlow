"""Body-free request correlation; preserve streaming without buffering response bodies."""

import logging
import time
import uuid

log = logging.getLogger(__name__)


class RequestTraceMiddleware:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        supplied = dict(scope.get("headers", [])).get(b"x-request-id", b"")
        try:
            request_id = str(uuid.UUID(supplied.decode("ascii")))
        except (ValueError, UnicodeError):
            request_id = str(uuid.uuid4())
        started = time.monotonic()
        status = 500

        async def traced(message):
            nonlocal status
            if message["type"] == "http.response.start":
                status = message["status"]
                message = dict(message)
                message["headers"] = list(message.get("headers", [])) + [
                    (b"x-request-id", request_id.encode())
                ]
            await send(message)

        try:
            await self.app(scope, receive, traced)
        finally:
            route = getattr(scope.get("route"), "path", "UNMATCHED")
            log.info(
                "request_id=%s operation=%s status=%s elapsed_ms=%d",
                request_id,
                route,
                status,
                (time.monotonic() - started) * 1000,
            )
