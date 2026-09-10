import asyncio
import json

import httpx
import pytest

from careflow_sdk import ApiError, AsyncClient, Client, Query, StreamError

ORIGIN = "https://careflow.test"
ID = "00000000-0000-0000-0000-000000000001"


def test_request_contracts_and_upload(tmp_path):
    requests = []

    def handler(request):
        requests.append(request)
        assert request.headers["authorization"] == "Bearer test-token"
        return httpx.Response(200, json={"id": ID})

    file = tmp_path / "资料.txt"
    file.write_text("合成资料")
    with Client(ORIGIN, "test-token", transport=httpx.MockTransport(handler)) as client:
        client.knowledge_bases()
        client.create_knowledge_base("name", idempotency_key="create")
        client.upload(ID, file, idempotency_key="upload")
        client.job(ID)
        client.index(ID, idempotency_key="index")
        client.publish(ID, ID, 3, 2, idempotency_key="publish")
        client.search(Query("问题", minimum_rerank_score=0.5), idempotency_key="search")
    assert [r.url.path for r in requests] == [
        "/api/v1/knowledge-bases",
        "/api/v1/knowledge-bases",
        f"/api/v1/knowledge-bases/{ID}/documents",
        f"/api/v1/jobs/{ID}",
        f"/api/v1/document-versions/{ID}/index",
        f"/api/v1/documents/{ID}/publications",
        "/api/v1/retrieval/search",
    ]
    assert requests[2].headers["idempotency-key"] == "upload"
    assert b'name="file"' in requests[2].content
    assert "合成资料".encode() in requests[2].content
    assert json.loads(requests[-1].content)["minimum_rerank_score"] == 0.5
    assert json.loads(requests[-2].content)["revision"] == 3
    assert json.loads(requests[-2].content)["version_revision"] == 2


@pytest.mark.parametrize("status", [401, 409, 429, 503, 302])
def test_errors_are_not_retried_or_redirected(status):
    calls = []

    def handler(request):
        calls.append(request)
        return httpx.Response(
            status,
            headers={"Location": "https://other.test"},
            json={"code": "TEST", "request_id": "trace", "message": "private"},
        )

    with Client(ORIGIN, "test-token", transport=httpx.MockTransport(handler)) as client:
        with pytest.raises(ApiError) as error:
            client.search(Query("private question"))
    assert len(calls) == 1
    assert error.value.status == status
    assert error.value.request_id == "trace"
    assert "private" not in str(error.value)


@pytest.mark.parametrize(
    "stream,success",
    [
        ('event:delta\r\ndata:{"text":"你好"}\r\n\r\nevent:done\ndata:{}\n\n', True),
        ('event:delta\ndata:{"text":"partial"}\n\n', False),
        ('event:error\ndata:{"message":"private"}\n\n', False),
        ("event:delta\ndata:invalid\n\n", False),
        ("event:done\ndata:{}", False),
    ],
)
def test_sync_and_async_stream_contract(stream, success):
    def handler(request):
        assert request.headers["idempotency-key"] == "stable"
        return httpx.Response(
            200, headers={"content-type": "text/event-stream"}, content=stream.encode()
        )

    with Client(ORIGIN, "token", transport=httpx.MockTransport(handler)) as client:
        if success:
            with client.answer(Query("test"), idempotency_key="stable") as events:
                assert [e.name for e in events] == ["delta", "done"]
        else:
            with (
                pytest.raises(StreamError),
                client.answer(Query("test"), idempotency_key="stable") as events,
            ):
                list(events)

    async def run():
        async with AsyncClient(
            ORIGIN, "token", transport=httpx.MockTransport(handler)
        ) as client:
            if success:
                async with client.answer(
                    Query("test"), idempotency_key="stable"
                ) as events:
                    assert [e.name async for e in events] == ["delta", "done"]
            else:
                with pytest.raises(StreamError):
                    async with client.answer(
                        Query("test"), idempotency_key="stable"
                    ) as events:
                        _ = [e async for e in events]

    asyncio.run(run())


def test_async_request_and_early_stream_close():
    responses = []

    def handler(request):
        if request.url.path.endswith("answers"):
            response = httpx.Response(
                200,
                headers={"content-type": "text/event-stream"},
                content=b"event:delta\ndata:{}\n\n",
            )
            responses.append(response)
            return response
        return httpx.Response(200, json={"evidence": []})

    async def run():
        async with AsyncClient(
            ORIGIN, "token", transport=httpx.MockTransport(handler)
        ) as client:
            assert await client.search(Query("test")) == {"evidence": []}
            async with client.answer(Query("test")) as events:
                async for _ in events:
                    break
            assert responses[-1].is_closed

    asyncio.run(run())


def test_rejects_credential_origins_and_path_injection():
    with pytest.raises(ValueError):
        Client("https://user:password@careflow.test", "token")
    with Client(ORIGIN, "token") as client:
        with pytest.raises(ValueError):
            client.job("../credentials")


def test_async_management_routes(tmp_path):
    calls = []
    file = tmp_path / "manual.txt"
    file.write_text("synthetic")

    def handler(request):
        calls.append(request)
        return httpx.Response(200, json={"id": ID})

    async def run():
        async with AsyncClient(
            ORIGIN, "token", transport=httpx.MockTransport(handler)
        ) as client:
            await client.knowledge_bases()
            await client.create_knowledge_base("test")
            await client.upload(ID, file)
            await client.job(ID)
            await client.index(ID)
            await client.publish(ID, ID, 0, 0)

    asyncio.run(run())
    assert len(calls) == 6
    assert b"synthetic" in calls[2].content
    assert json.loads(calls[5].content)["version_id"] == ID


def test_stream_fragmentation_and_context_closes_transport():
    class Fragmented(httpx.SyncByteStream):
        closed = False

        def __iter__(self):
            for byte in 'event:delta\r\ndata:{"text":"中文"}\r\n\r\nevent:done\r\ndata:{}\r\n\r\n'.encode():
                yield bytes([byte])

        def close(self):
            self.closed = True

    stream = Fragmented()
    with Client(
        ORIGIN,
        "token",
        transport=httpx.MockTransport(
            lambda _: httpx.Response(
                200, headers={"content-type": "text/event-stream"}, stream=stream
            )
        ),
    ) as client:
        with client.answer(Query("test")) as events:
            result = list(events)
    assert result[0].data["text"] == "中文"
    assert stream.closed


def test_routes_and_query_fields_exist_in_openapi():
    from pathlib import Path

    schema = json.loads(
        (Path(__file__).resolve().parents[3] / "docs/openapi.json").read_text()
    )
    for path, method in [
        ("/knowledge-bases", "get"),
        ("/knowledge-bases", "post"),
        ("/knowledge-bases/{id}/documents", "post"),
        ("/jobs/{id}", "get"),
        ("/document-versions/{id}/index", "post"),
        ("/documents/{id}/publications", "post"),
        ("/retrieval/search", "post"),
        ("/answers", "post"),
    ]:
        assert method in schema["paths"]["/api/v1" + path]
    assert set(Query.__dataclass_fields__) <= set(
        schema["components"]["schemas"]["Query"]["properties"]
    )


def test_document_versions_preserves_revision_in_sync_and_async_clients():
    def handler(request):
        assert request.url.path == f"/api/v1/documents/{ID}/versions"
        return httpx.Response(200, json=[{"id": ID, "revision": 7}])

    with Client(ORIGIN, "test-token", transport=httpx.MockTransport(handler)) as client:
        assert client.document_versions(ID)[0]["revision"] == 7

    async def check():
        async with AsyncClient(
            ORIGIN, "test-token", transport=httpx.MockTransport(handler)
        ) as client:
            assert (await client.document_versions(ID))[0]["revision"] == 7

    asyncio.run(check())
