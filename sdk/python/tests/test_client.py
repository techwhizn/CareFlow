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
        (
            'event:delta\r\ndata:{"text":"你好"}\r\n\r\nevent:usage\ndata:{"total_tokens":16}\n\nevent:done\ndata:{}\n\n',
            True,
        ),
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
                assert [e.name for e in events] == ["delta", "usage", "done"]
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
                    assert [e.name async for e in events] == ["delta", "usage", "done"]
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
        ("/knowledge-bases/{kb}/configurations", "get"),
        ("/knowledge-bases/{kb}/configurations", "post"),
        ("/knowledge-bases/{kb}/configuration-models", "get"),
        ("/knowledge-bases/{kb}/configurations/{id}/impact", "get"),
        ("/knowledge-bases/{kb}/configuration-publications", "post"),
        ("/document-versions/{id}/reprocess", "post"),
        ("/document-versions/{id}/configuration-binding", "post"),
        ("/document-versions/{id}/contexts", "get"),
        ("/document-versions/{id}/contexts/{context}", "get"),
        ("/document-versions/{version}/faqs", "post"),
        ("/document-versions/{version}/faqs/{context}", "put"),
        ("/document-versions/{version}/contexts/{context}/detach", "post"),
        ("/document-versions/{version}/quality", "get"),
        ("/document-versions/{version}/chunk-operations", "post"),
        ("/document-versions/{version}/content-conflicts", "get"),
        ("/document-versions/{version}/chunk-changes", "get"),
        ("/document-versions/{version}/content-conflicts/{id}/resolution", "post"),
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


def test_typed_metadata_filter_serializes_closed_request_shape():
    from careflow_sdk import MetadataFilter

    def handler(request):
        payload = json.loads(request.content)
        assert payload["filters"] == [
            {"field": "product_models", "operator": "in", "value": ["CF-100", "CF-200"]}
        ]
        return httpx.Response(200, json={"evidence": []})

    with Client(ORIGIN, "test-token", transport=httpx.MockTransport(handler)) as client:
        client.search(
            Query(
                "fixture",
                filters=(MetadataFilter("product_models", "in", ("CF-100", "CF-200")),),
            )
        )


def test_configuration_revision_and_reprocess_contracts_sync_and_async():
    from careflow_sdk.configuration import KnowledgeConfiguration, ModelReferences

    captured = []

    def handler(request):
        captured.append(request)
        return httpx.Response(200, json={"id": ID})

    configuration = KnowledgeConfiguration(
        "synthetic", ModelReferences(ID, ID, ID, 2, 3, 4)
    )
    with Client(ORIGIN, "test-token", transport=httpx.MockTransport(handler)) as client:
        client.knowledge_configurations(ID)
        client.configuration_models(ID)
        client.create_knowledge_configuration(
            ID, configuration, idempotency_key="draft"
        )
        client.configuration_impact(ID, ID)
        client.publish_knowledge_configuration(
            ID, ID, 7, "synthetic", idempotency_key="publish-config"
        )
        client.reprocess(ID, idempotency_key="reprocess")

    async def run():
        async with AsyncClient(
            ORIGIN, "test-token", transport=httpx.MockTransport(handler)
        ) as client:
            await client.create_knowledge_configuration(
                ID, configuration, idempotency_key="async-draft"
            )
            await client.publish_knowledge_configuration(
                ID, ID, 8, "synthetic", idempotency_key="async-publish"
            )
            await client.reprocess(ID, idempotency_key="async-reprocess")

    asyncio.run(run())
    assert json.loads(captured[2].content)["models"]["embedding_profile_revision"] == 2
    assert json.loads(captured[4].content)["revision"] == 7
    assert captured[5].url.path.endswith(f"/{ID}/reprocess")
    assert captured[-1].headers["idempotency-key"] == "async-reprocess"
    assert "api_key" not in captured[2].content.decode()


def test_configuration_binding_preserves_reviewed_revision_sync_and_async():
    def handler(request):
        assert (
            request.url.path == f"/api/v1/document-versions/{ID}/configuration-binding"
        )
        assert json.loads(request.content) == {"revision": 9}
        assert request.headers["idempotency-key"] == "bind"
        return httpx.Response(200, json={"revision": 10})

    with Client(ORIGIN, "test-token", transport=httpx.MockTransport(handler)) as client:
        assert (
            client.bind_configuration(ID, 9, idempotency_key="bind")["revision"] == 10
        )

    async def run():
        async with AsyncClient(
            ORIGIN, "test-token", transport=httpx.MockTransport(handler)
        ) as client:
            assert (await client.bind_configuration(ID, 9, idempotency_key="bind"))[
                "revision"
            ] == 10

    asyncio.run(run())


def test_context_and_faq_management_use_reviewed_revision_in_both_clients():
    from careflow_sdk import FaqInput

    requests = []

    def handler(request):
        requests.append(request)
        return httpx.Response(200, json={"id": ID})

    entry = FaqInput(9, "question", "answer", "manual", ("similar",))
    with Client(ORIGIN, "test-token", transport=httpx.MockTransport(handler)) as client:
        client.document_contexts(ID, page=2)
        client.document_context(ID, ID)
        client.save_faq(ID, entry, idempotency_key="create-faq")
        client.save_faq(ID, entry, context_id=ID, idempotency_key="update-faq")
        client.detach_context(ID, ID, 9, "separate", idempotency_key="detach")

    async def run():
        async with AsyncClient(
            ORIGIN, "test-token", transport=httpx.MockTransport(handler)
        ) as client:
            await client.document_contexts(ID, page=2)
            await client.document_context(ID, ID)
            await client.save_faq(ID, entry, idempotency_key="create-faq")
            await client.save_faq(
                ID, entry, context_id=ID, idempotency_key="update-faq"
            )
            await client.detach_context(ID, ID, 9, "separate", idempotency_key="detach")

    asyncio.run(run())
    for group in [requests[:5], requests[5:]]:
        assert group[0].url.params["page"] == "2"
        assert group[2].method == "POST" and group[3].method == "PUT"
        assert json.loads(group[2].content)["revision"] == 9
        assert json.loads(group[3].content)["alternatives"] == ["similar"]
        assert group[4].url.path.endswith("/detach")


def test_quality_routes_in_sync_and_async_clients():
    def handler(request):
        assert request.url.path == f"/api/v1/document-versions/{ID}/quality"
        assert request.url.params["page"] == "2"
        return httpx.Response(200, json={"issue_count": 203})

    with Client(ORIGIN, "test-token", transport=httpx.MockTransport(handler)) as client:
        assert client.document_quality(ID, page=2)["issue_count"] == 203

    async def run():
        async with AsyncClient(
            ORIGIN, "test-token", transport=httpx.MockTransport(handler)
        ) as client:
            assert (await client.document_quality(ID, page=2))["issue_count"] == 203

    asyncio.run(run())


def test_chunk_operations_and_conflicts_preserve_target_revisions():
    from careflow_sdk import ChunkOperation, ChunkRef, ConflictResolution

    seen = []

    def handler(request):
        seen.append(request)
        return httpx.Response(200, json={"revision": 4})

    operation = ChunkOperation(
        3, "SPLIT", (ChunkRef(ID, 2),), "review", split_offsets=(8,)
    )
    resolution = ConflictResolution(3, "APPLY_TO_CHUNK", "review", ChunkRef(ID, 2))
    with Client(ORIGIN, "test-token", transport=httpx.MockTransport(handler)) as client:
        client.chunk_operation(ID, operation, idempotency_key="operation")
        client.resolve_content_conflict(ID, ID, resolution, idempotency_key="resolve")
        client.content_conflicts(ID, page=1)
        client.chunk_changes(ID, page=2)

    async def run():
        async with AsyncClient(
            ORIGIN, "test-token", transport=httpx.MockTransport(handler)
        ) as client:
            await client.chunk_operation(ID, operation, idempotency_key="operation")
            await client.resolve_content_conflict(
                ID, ID, resolution, idempotency_key="resolve"
            )
            await client.content_conflicts(ID, page=1)
            await client.chunk_changes(ID, page=2)

    asyncio.run(run())
    for group in [seen[:4], seen[4:]]:
        assert group[0].url.path.endswith("/chunk-operations")
        assert json.loads(group[0].content)["chunks"] == [{"id": ID, "revision": 2}]
        assert json.loads(group[0].content)["split_offsets"] == [8]
        assert json.loads(group[1].content)["target"]["revision"] == 2
        assert group[2].url.params["page"] == "1"
        assert group[3].url.params["page"] == "2"


def test_python_character_boundaries_convert_for_non_bmp_split_offsets():
    from careflow_sdk.content import utf16_offset

    assert utf16_offset("A😀B", 2) == 3
    with pytest.raises(ValueError):
        utf16_offset("abc", 4)


def test_index_rebuild_uses_observed_generation_and_revision_sync_and_async():
    from careflow_sdk import IndexRebuild

    seen = []

    def handler(request):
        seen.append(request)
        return httpx.Response(200, json={"job_id": ID})

    with Client(ORIGIN, "test-token", transport=httpx.MockTransport(handler)) as client:
        client.rebuild_index(
            ID, IndexRebuild(4, "repair", ID), idempotency_key="rebuild"
        )
        client.check_index(ID)
        client.index_history(ID)

    async def run():
        async with AsyncClient(
            ORIGIN, "test-token", transport=httpx.MockTransport(handler)
        ) as client:
            await client.rebuild_index(
                ID, IndexRebuild(4, "repair", ID), idempotency_key="rebuild"
            )
            await client.check_index(ID)
            await client.index_history(ID)

    asyncio.run(run())
    for group in (seen[:3], seen[3:]):
        assert group[0].url.path.endswith("/index/rebuild")
        assert json.loads(group[0].content) == {
            "revision": 4,
            "reason": "repair",
            "expected_generation_id": ID,
        }
        assert group[0].headers["Idempotency-Key"] == "rebuild"
        assert group[1].url.path.endswith("/index/checks") and group[1].method == "POST"
        assert group[2].url.path.endswith("/index/history") and group[2].method == "GET"


def test_cleanup_contract_sync_and_async():
    seen = []

    def handler(request):
        seen.append(request)
        return httpx.Response(200, json=[])

    with Client(ORIGIN, "test-token", transport=httpx.MockTransport(handler)) as client:
        client.cleanup_requests()
        client.retry_cleanup(ID, "storage recovered", idempotency_key="retry")

    async def run():
        async with AsyncClient(
            ORIGIN, "test-token", transport=httpx.MockTransport(handler)
        ) as client:
            await client.cleanup_requests()
            await client.retry_cleanup(ID, "storage recovered", idempotency_key="retry")

    asyncio.run(run())
    for group in (seen[:2], seen[2:]):
        assert group[0].method == "GET" and group[0].url.path.endswith(
            "/cleanup-requests"
        )
        assert group[1].url.path.endswith(f"/cleanup-requests/{ID}/retry")
        assert json.loads(group[1].content) == {"reason": "storage recovered"}
        assert group[1].headers["Idempotency-Key"] == "retry"


def test_operations_status_uses_the_same_authorized_public_api():
    calls = []

    def handler(request):
        calls.append(request.url.path)
        return httpx.Response(200, json={"jobs": [], "cleanup": [], "generation": []})

    with Client(ORIGIN, "test-token", transport=httpx.MockTransport(handler)) as client:
        assert client.operations_status()["jobs"] == []

    async def run():
        async with AsyncClient(
            ORIGIN, "test-token", transport=httpx.MockTransport(handler)
        ) as client:
            assert (await client.operations_status())["generation"] == []

    asyncio.run(run())
    assert calls == ["/api/v1/operations/status"] * 2


def test_conversation_and_saved_answer_endpoints():
    requests = []

    def handler(request):
        requests.append(request)
        return httpx.Response(200, json={"id": ID})

    with Client(ORIGIN, "test-token", transport=httpx.MockTransport(handler)) as client:
        client.create_conversation(knowledge_base_ids=(ID,))
        client.conversations()
        client.conversation(ID)
        client.answer_history(conversation_id=ID)
        client.saved_answer(ID)
    assert json.loads(requests[0].content)["knowledge_base_ids"] == [ID]
    assert requests[2].url.path == f"/api/v1/conversations/{ID}"
    assert requests[3].url.params["conversation_id"] == ID
    assert requests[4].url.path == f"/api/v1/answers/{ID}"

    async def run():
        async with AsyncClient(
            ORIGIN, "test-token", transport=httpx.MockTransport(handler)
        ) as client:
            await client.create_conversation()
            await client.conversations()
            await client.conversation(ID)
            await client.answer_history(conversation_id=ID)
            await client.saved_answer(ID)

    asyncio.run(run())
    assert len(requests) == 10


def test_citation_source_requires_explicit_answer_and_evidence_identity():
    seen = []

    def handler(request):
        seen.append(request.url.path)
        return httpx.Response(200, json={"source_snapshot": True})

    with Client(ORIGIN, "test-token", transport=httpx.MockTransport(handler)) as client:
        assert client.citation_source(ID, ID)["source_snapshot"]
    assert seen == [f"/api/v1/answers/{ID}/citations/{ID}"]
