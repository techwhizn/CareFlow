import asyncio
import json

import httpx

from careflow_sdk import AsyncClient, Client
from careflow_sdk.management import DocumentMetadata, PermissionChange

ID = "00000000-0000-0000-0000-000000000001"


def test_management_preserves_revisions_and_source_metadata_in_both_clients():
    seen = []

    def handler(request):
        seen.append(
            (
                request.method,
                request.url.path,
                dict(request.url.params),
                json.loads(request.content) if request.content else None,
            )
        )
        assert request.headers["authorization"] == "Bearer test-token"
        return httpx.Response(200, json={})

    metadata = DocumentMetadata("Synthetic", "fixture", "zh", (), ("CF-100",), 7)
    permissions = PermissionChange({ID: ["read"]}, 9)
    with Client(
        "https://careflow.test", "test-token", transport=httpx.MockTransport(handler)
    ) as client:
        management = client.management
        management.update_document_metadata(ID, metadata)
        management.update_document_permissions(ID, permissions)
        management.draft_version(ID)
        management.delete_document(ID, revision=11)
        management.document_metadata_history(ID, page=2)

    async def check():
        async with AsyncClient(
            "https://careflow.test",
            "test-token",
            transport=httpx.MockTransport(handler),
        ) as client:
            management = client.management
            await management.update_document_metadata(ID, metadata)
            await management.update_document_permissions(ID, permissions)
            await management.draft_version(ID)
            await management.delete_document(ID, revision=11)
            await management.document_metadata_history(ID, page=2)

    asyncio.run(check())
    assert seen[:5] == seen[5:]
    assert seen[0][3]["revision"] == 7
    assert seen[0][3]["valid_from"] is None
    assert seen[1][3] == {"grants": {ID: ["read"]}, "revision": 9}
    assert seen[3][0] == "DELETE" and seen[3][2] == {"revision": "11"}
    assert seen[4][2] == {"page": "2"}


def test_source_transfer_and_replacement_use_public_authorization(tmp_path):
    import io

    import pytest

    from careflow_sdk import ApiError

    source = tmp_path / "synthetic.txt"
    source.write_bytes(b"synthetic source")
    seen = []

    def handler(request):
        seen.append(request)
        if request.method == "GET":
            return httpx.Response(200, content=b"synthetic source")
        return httpx.Response(200, json={"version_id": ID})

    with Client(
        "https://careflow.test", "test-token", transport=httpx.MockTransport(handler)
    ) as client:
        sink = io.BytesIO()
        assert client.management.download_source(ID, sink) == 16
        assert sink.getvalue() == source.read_bytes()
        client.management.replace_document(
            ID, source, idempotency_key="replacement", billing_revision=2
        )
    assert seen[1].url.path == f"/api/v1/documents/{ID}/versions"
    assert seen[1].url.params["billing_revision"] == "2"
    assert seen[1].headers["idempotency-key"] == "replacement"
    assert b"synthetic source" in seen[1].content

    async def denied():
        async with AsyncClient(
            "https://careflow.test",
            "test-token",
            transport=httpx.MockTransport(
                lambda _: httpx.Response(403, json={"code": "FORBIDDEN"})
            ),
        ) as client:
            sink = io.BytesIO()
            with pytest.raises(ApiError):
                await client.management.download_source(ID, sink)
            assert sink.getvalue() == b""

    asyncio.run(denied())
