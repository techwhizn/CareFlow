import logging
import uuid

from fastapi import FastAPI
from fastapi.testclient import TestClient

from careflow.observability import RequestTraceMiddleware


def test_trace_keeps_id_without_logging_body_or_query(caplog):
    app = FastAPI()
    app.add_middleware(RequestTraceMiddleware)

    @app.post("/test")
    def handler():
        return {"ok": True}

    trace = str(uuid.uuid4())
    with (
        caplog.at_level(logging.INFO, logger="careflow.observability"),
        TestClient(app) as client,
    ):
        response = client.post(
            "/test?private=secret-query",
            content="secret-body",
            headers={"X-Request-ID": trace},
        )
        assert response.headers["x-request-id"] == trace
        assert trace in caplog.text and "status=200" in caplog.text
        assert "secret-query" not in caplog.text and "secret-body" not in caplog.text
        assert (
            client.post("/test", headers={"X-Request-ID": "malformed"}).headers[
                "x-request-id"
            ]
            != "malformed"
        )
