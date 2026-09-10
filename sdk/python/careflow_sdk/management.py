"""Management inputs and explicit operations over the shared authenticated transport."""

from dataclasses import asdict, dataclass
from typing import Literal

from . import _check, _id


@dataclass(frozen=True)
class KnowledgeAttributes:
    name: str
    description: str
    language: str
    tags: tuple[str, ...]
    owner_id: str
    revision: int


@dataclass(frozen=True)
class DocumentMetadata:
    title: str
    source: str
    language: str
    tags: tuple[str, ...]
    product_models: tuple[str, ...]
    revision: int
    valid_from: str | None = None
    valid_until: str | None = None


@dataclass(frozen=True)
class PermissionChange:
    grants: dict[str, list[str]]
    revision: int


@dataclass(frozen=True)
class MemberChange:
    name: str
    role: Literal["OWNER", "ADMIN", "KNOWLEDGE_MANAGER", "DEVELOPER", "USER", "OPS"]
    state: Literal["ACTIVE", "DISABLED", "REMOVED"]
    revision: int


class ManagementClient:
    def __init__(self, client):
        self._client = client

    def knowledge_settings(self, resource_id: str):
        return self._client._request(
            "GET", f"knowledge-bases/{_id(resource_id)}/settings"
        )

    def knowledge_overview(self, resource_id: str):
        return self._client._request(
            "GET", f"knowledge-bases/{_id(resource_id)}/overview"
        )

    def knowledge_impact(self, resource_id: str):
        return self._client._request(
            "GET", f"knowledge-bases/{_id(resource_id)}/impact"
        )

    def document_metadata(self, resource_id: str):
        return self._client._request("GET", f"documents/{_id(resource_id)}/metadata")

    def document_publications(self, resource_id: str):
        return self._client._request(
            "GET", f"documents/{_id(resource_id)}/publications"
        )

    def draft_version(self, resource_id: str):
        return self._client._request(
            "POST", f"document-versions/{_id(resource_id)}/draft"
        )

    def knowledge_authorization(self, resource_id: str):
        return self._client._request(
            "GET", f"knowledge-bases/{_id(resource_id)}/authorization"
        )

    def document_authorization(self, resource_id: str):
        return self._client._request(
            "GET", f"documents/{_id(resource_id)}/authorization"
        )

    def update_knowledge(self, resource_id: str, change: KnowledgeAttributes):
        return self._client._request(
            "PUT", f"knowledge-bases/{_id(resource_id)}", json=asdict(change)
        )

    def update_document_metadata(self, resource_id: str, change: DocumentMetadata):
        return self._client._request(
            "PUT", f"documents/{_id(resource_id)}/metadata", json=asdict(change)
        )

    def update_knowledge_permissions(self, resource_id: str, change: PermissionChange):
        return self._client._request(
            "PUT",
            f"knowledge-bases/{_id(resource_id)}/permissions",
            json=asdict(change),
        )

    def update_document_permissions(self, resource_id: str, change: PermissionChange):
        return self._client._request(
            "PUT", f"documents/{_id(resource_id)}/permissions", json=asdict(change)
        )

    def update_member(self, resource_id: str, change: MemberChange):
        return self._client._request(
            "PUT", f"members/{_id(resource_id)}", json=asdict(change)
        )

    def members(self):
        return self._client._request("GET", "members")

    def create_member(self, name: str, role: str):
        return self._client._request(
            "POST", "members", json={"name": name, "role": role}
        )

    def knowledge_state(
        self,
        knowledge_base_id: str,
        *,
        status: Literal["ACTIVE", "ARCHIVED", "DELETED"],
        revision: int,
    ):
        return self._client._request(
            "PUT",
            f"knowledge-bases/{_id(knowledge_base_id)}/state",
            json={"status": status, "revision": revision},
        )

    def delete_document(self, document_id: str, *, revision: int):
        return self._client._request(
            "DELETE", f"documents/{_id(document_id)}", params={"revision": revision}
        )

    def document_metadata_history(self, document_id: str, *, page: int = 0):
        if isinstance(page, bool) or not isinstance(page, int) or page < 0:
            raise ValueError("page must be a nonnegative integer")
        return self._client._request(
            "GET",
            f"documents/{_id(document_id)}/metadata-history",
            params={"page": page},
        )

    def replace_document(
        self, document_id: str, file, *, idempotency_key=None, billing_revision=None
    ):
        return self._client._upload_to(
            f"documents/{_id(document_id)}/versions",
            file,
            idempotency_key=idempotency_key,
            billing_revision=billing_revision,
        )

    def download_source(self, version_id: str, sink) -> int:
        """Write authorized source bytes to a caller-owned binary sink; failures may leave partial bytes."""
        total = 0
        with self._client._http.stream(
            "GET", f"document-versions/{_id(version_id)}/source"
        ) as response:
            if response.status_code >= 300:
                response.read()
            _check(response)
            for data in response.iter_bytes():
                written = sink.write(data)
                if written != len(data):
                    raise OSError("Source sink did not accept all bytes")
                total += written
        return total


class AsyncManagementClient:
    def __init__(self, client):
        self._client = client

    async def knowledge_settings(self, resource_id: str):
        return await self._client._request(
            "GET", f"knowledge-bases/{_id(resource_id)}/settings"
        )

    async def knowledge_overview(self, resource_id: str):
        return await self._client._request(
            "GET", f"knowledge-bases/{_id(resource_id)}/overview"
        )

    async def knowledge_impact(self, resource_id: str):
        return await self._client._request(
            "GET", f"knowledge-bases/{_id(resource_id)}/impact"
        )

    async def document_metadata(self, resource_id: str):
        return await self._client._request(
            "GET", f"documents/{_id(resource_id)}/metadata"
        )

    async def document_publications(self, resource_id: str):
        return await self._client._request(
            "GET", f"documents/{_id(resource_id)}/publications"
        )

    async def draft_version(self, resource_id: str):
        return await self._client._request(
            "POST", f"document-versions/{_id(resource_id)}/draft"
        )

    async def knowledge_authorization(self, resource_id: str):
        return await self._client._request(
            "GET", f"knowledge-bases/{_id(resource_id)}/authorization"
        )

    async def document_authorization(self, resource_id: str):
        return await self._client._request(
            "GET", f"documents/{_id(resource_id)}/authorization"
        )

    async def update_knowledge(self, resource_id: str, change: KnowledgeAttributes):
        return await self._client._request(
            "PUT", f"knowledge-bases/{_id(resource_id)}", json=asdict(change)
        )

    async def update_document_metadata(
        self, resource_id: str, change: DocumentMetadata
    ):
        return await self._client._request(
            "PUT", f"documents/{_id(resource_id)}/metadata", json=asdict(change)
        )

    async def update_knowledge_permissions(
        self, resource_id: str, change: PermissionChange
    ):
        return await self._client._request(
            "PUT",
            f"knowledge-bases/{_id(resource_id)}/permissions",
            json=asdict(change),
        )

    async def update_document_permissions(
        self, resource_id: str, change: PermissionChange
    ):
        return await self._client._request(
            "PUT", f"documents/{_id(resource_id)}/permissions", json=asdict(change)
        )

    async def update_member(self, resource_id: str, change: MemberChange):
        return await self._client._request(
            "PUT", f"members/{_id(resource_id)}", json=asdict(change)
        )

    async def members(self):
        return await self._client._request("GET", "members")

    async def create_member(self, name: str, role: str):
        return await self._client._request(
            "POST", "members", json={"name": name, "role": role}
        )

    async def knowledge_state(
        self,
        knowledge_base_id: str,
        *,
        status: Literal["ACTIVE", "ARCHIVED", "DELETED"],
        revision: int,
    ):
        return await self._client._request(
            "PUT",
            f"knowledge-bases/{_id(knowledge_base_id)}/state",
            json={"status": status, "revision": revision},
        )

    async def delete_document(self, document_id: str, *, revision: int):
        return await self._client._request(
            "DELETE", f"documents/{_id(document_id)}", params={"revision": revision}
        )

    async def document_metadata_history(self, document_id: str, *, page: int = 0):
        if isinstance(page, bool) or not isinstance(page, int) or page < 0:
            raise ValueError("page must be a nonnegative integer")
        return await self._client._request(
            "GET",
            f"documents/{_id(document_id)}/metadata-history",
            params={"page": page},
        )

    async def replace_document(
        self, document_id: str, file, *, idempotency_key=None, billing_revision=None
    ):
        return await self._client._upload_to(
            f"documents/{_id(document_id)}/versions",
            file,
            idempotency_key=idempotency_key,
            billing_revision=billing_revision,
        )

    async def download_source(self, version_id: str, sink) -> int:
        """Write authorized source bytes to a caller-owned binary sink; failures may leave partial bytes."""
        total = 0
        async with self._client._http.stream(
            "GET", f"document-versions/{_id(version_id)}/source"
        ) as response:
            if response.status_code >= 300:
                await response.aread()
            _check(response)
            async for data in response.aiter_bytes():
                written = sink.write(data)
                if written != len(data):
                    raise OSError("Source sink did not accept all bytes")
                total += written
        return total
