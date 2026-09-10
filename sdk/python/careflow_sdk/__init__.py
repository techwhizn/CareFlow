"""CareFlow public API clients. No implicit retries or credential redirects."""

import json
import uuid
from contextlib import asynccontextmanager, contextmanager
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Literal
from urllib.parse import urlsplit

import httpx

from .configuration import KnowledgeConfiguration as KnowledgeConfiguration
from .content import ChunkEdit as ChunkEdit
from .content import ChunkOperation as ChunkOperation
from .content import ChunkRef as ChunkRef
from .content import ConflictResolution as ConflictResolution
from .content import FaqInput as FaqInput
from .content import IndexRebuild as IndexRebuild


class ApiError(RuntimeError):
    def __init__(self, status: int, code: str, request_id: str | None = None):
        # Do not embed server bodies, questions, URLs or credentials in exceptions.
        super().__init__(f"CareFlow request failed (HTTP {status})")
        self.status = status
        self.code = code
        self.request_id = request_id


class StreamError(RuntimeError):
    pass


@dataclass(frozen=True)
class MetadataFilter:
    field: Literal[
        "title",
        "source",
        "language",
        "tags",
        "product_models",
        "valid_from",
        "valid_until",
    ]
    operator: Literal["eq", "in", "contains", "gte", "lte"]
    value: str | tuple[str, ...]


@dataclass(frozen=True)
class Query:
    query: str
    application_id: str | None = None
    knowledge_base_ids: tuple[str, ...] = ()
    mode: str | None = None
    limit: int = 6
    debug: bool = False
    minimum_rerank_score: float | None = None
    filters: tuple[MetadataFilter, ...] = ()
    conversation_id: str | None = None


@dataclass(frozen=True)
class Event:
    name: str
    data: dict[str, Any]


def _headers(key):
    return {"Idempotency-Key": key or str(uuid.uuid4())}


def _id(value):
    return str(uuid.UUID(value))


def _options(base_url, token, timeout, transport):
    parsed = urlsplit(base_url)
    if (
        parsed.scheme not in {"http", "https"}
        or not parsed.hostname
        or parsed.username
        or parsed.password
        or parsed.query
        or parsed.fragment
        or parsed.path not in {"", "/"}
    ):
        raise ValueError(
            "base_url must be an HTTP(S) origin without credentials or path"
        )
    if not token or "\n" in token or "\r" in token:
        raise ValueError("A nonempty token is required")
    return dict(
        base_url=base_url.rstrip("/") + "/api/v1/",
        headers={"Authorization": "Bearer " + token},
        timeout=timeout,
        follow_redirects=False,
        transport=transport,
    )


def _check(response):
    if not 200 <= response.status_code < 300:
        try:
            body = response.json()
        except (ValueError, UnicodeError):
            body = {}
        if not isinstance(body, dict):
            body = {}
        raise ApiError(
            response.status_code, body.get("code", "HTTP_ERROR"), body.get("request_id")
        )


class _SSE:
    def __init__(self):
        self.name, self.data, self.size = "message", [], 0
        self.done = False

    def line(self, line):
        if line == "":
            if not self.data:
                self.name = "message"
                self.size = 0
                return None
            try:
                payload = json.loads("\n".join(self.data))
            except ValueError as exc:
                raise StreamError("Invalid SSE JSON") from exc
            if not isinstance(payload, dict):
                raise StreamError("Invalid SSE payload")
            event = Event(self.name, payload)
            self.name, self.data, self.size = "message", [], 0
            if event.name == "error":
                raise StreamError("Server reported an incomplete answer")
            if event.name == "done":
                self.done = True
            return event
        self.size += len(line)
        if self.size > 1024 * 1024:
            raise StreamError("SSE event exceeds size limit")
        field, _, value = line.partition(":")
        value = value[1:] if value.startswith(" ") else value
        if field == "event":
            self.name = value
        elif field == "data":
            self.data.append(value)
        return None


def _stream_type(response):
    if (
        response.headers.get("content-type", "").split(";", 1)[0].strip()
        != "text/event-stream"
    ):
        raise StreamError("Expected an SSE response")


class Client:
    def __init__(self, base_url, token, *, timeout=120.0, transport=None):
        self._http = httpx.Client(**_options(base_url, token, timeout, transport))

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()

    def close(self):
        self._http.close()

    def _request(self, method, path, **kwargs):
        response = self._http.request(method, path, **kwargs)
        _check(response)
        return response.json() if response.content else None

    def create_conversation(self, *, application_id=None, knowledge_base_ids=()):
        return self._request(
            "POST",
            "conversations",
            json=dict(
                application_id=application_id,
                knowledge_base_ids=list(knowledge_base_ids),
            ),
        )

    def conversations(self):
        return self._request("GET", "conversations")

    def conversation(self, conversation_id):
        return self._request("GET", f"conversations/{_id(conversation_id)}")

    def answer_history(self, *, conversation_id=None):
        params = {"conversation_id": _id(conversation_id)} if conversation_id else {}
        return self._request("GET", "answers", params=params)

    def submit_feedback(
        self, answer_id, feedback, *, reason=None, comment="", revision=0
    ):
        return self._request(
            "POST",
            f"answers/{_id(answer_id)}/feedback",
            json=dict(
                feedback=feedback, reason=reason, comment=comment, revision=revision
            ),
        )

    def create_improvement(
        self, source_kind, source_id, knowledge_base_id, *, description=""
    ):
        return self._request(
            "POST",
            "improvements",
            json=dict(
                source_kind=source_kind,
                source_id=_id(source_id),
                knowledge_base_id=_id(knowledge_base_id),
                description=description,
            ),
        )

    def improvements(self):
        return self._request("GET", "improvements")

    def improvement(self, task_id):
        return self._request("GET", f"improvements/{_id(task_id)}")

    def improvement_assignees(self, task_id):
        return self._request("GET", f"improvements/{_id(task_id)}/assignees")

    def update_improvement(
        self, task_id, *, revision, state, assignee_id=None, resolution=""
    ):
        return self._request(
            "PUT",
            f"improvements/{_id(task_id)}",
            json=dict(
                revision=revision,
                state=state,
                assignee_id=_id(assignee_id) if assignee_id else None,
                resolution=resolution,
            ),
        )

    def citation_source(self, answer_id, citation_id):
        return self._request(
            "GET", f"answers/{_id(answer_id)}/citations/{_id(citation_id)}"
        )

    def saved_answer(self, answer_id):
        return self._request("GET", f"answers/{_id(answer_id)}")

    def operations_status(self):
        return self._request("GET", "operations/status")

    def knowledge_bases(self):
        return self._request("GET", "knowledge-bases")

    def create_knowledge_base(self, name, description="", *, idempotency_key=None):
        return self._request(
            "POST",
            "knowledge-bases",
            json=dict(name=name, description=description),
            headers=_headers(idempotency_key),
        )

    def upload(self, knowledge_base_id, file, *, idempotency_key=None):
        path = Path(file)
        with path.open("rb") as source:
            return self._request(
                "POST",
                f"knowledge-bases/{_id(knowledge_base_id)}/documents",
                files={"file": (path.name, source, "application/octet-stream")},
                headers=_headers(idempotency_key),
            )

    def knowledge_configurations(self, knowledge_base_id):
        return self._request(
            "GET", f"knowledge-bases/{_id(knowledge_base_id)}/configurations"
        )

    def configuration_models(self, knowledge_base_id):
        return self._request(
            "GET", f"knowledge-bases/{_id(knowledge_base_id)}/configuration-models"
        )

    def create_knowledge_configuration(
        self,
        knowledge_base_id,
        configuration: KnowledgeConfiguration,
        *,
        idempotency_key=None,
    ):
        return self._request(
            "POST",
            f"knowledge-bases/{_id(knowledge_base_id)}/configurations",
            json=asdict(configuration),
            headers=_headers(idempotency_key),
        )

    def configuration_impact(self, knowledge_base_id, configuration_id):
        return self._request(
            "GET",
            f"knowledge-bases/{_id(knowledge_base_id)}/configurations/{_id(configuration_id)}/impact",
        )

    def publish_knowledge_configuration(
        self,
        knowledge_base_id,
        configuration_id,
        revision,
        reason,
        *,
        idempotency_key=None,
    ):
        return self._request(
            "POST",
            f"knowledge-bases/{_id(knowledge_base_id)}/configuration-publications",
            json={
                "configuration_id": _id(configuration_id),
                "revision": revision,
                "reason": reason,
            },
            headers=_headers(idempotency_key),
        )

    def bind_configuration(self, version_id, revision, *, idempotency_key=None):
        return self._request(
            "POST",
            f"document-versions/{_id(version_id)}/configuration-binding",
            json={"revision": revision},
            headers=_headers(idempotency_key),
        )

    def reprocess(self, version_id, *, idempotency_key=None):
        return self._request(
            "POST",
            f"document-versions/{_id(version_id)}/reprocess",
            headers=_headers(idempotency_key),
        )

    def document_versions(self, document_id):
        return self._request("GET", f"documents/{_id(document_id)}/versions")

    def document_chunks(self, version_id, *, page=0):
        return self._request(
            "GET", f"document-versions/{_id(version_id)}/chunks", params={"page": page}
        )

    def edit_chunk(self, chunk_id, edit: ChunkEdit):
        return self._request("PUT", f"chunks/{_id(chunk_id)}", json=asdict(edit))

    def chunk_operation(
        self, version_id, operation: ChunkOperation, *, idempotency_key=None
    ):
        return self._request(
            "POST",
            f"document-versions/{_id(version_id)}/chunk-operations",
            json=asdict(operation),
            headers=_headers(idempotency_key),
        )

    def rebuild_index(self, version_id, input: IndexRebuild, *, idempotency_key=None):
        return self._request(
            "POST",
            f"document-versions/{_id(version_id)}/index/rebuild",
            json=asdict(input),
            headers=_headers(idempotency_key),
        )

    def check_index(self, version_id, *, idempotency_key=None):
        return self._request(
            "POST",
            f"document-versions/{_id(version_id)}/index/checks",
            headers=_headers(idempotency_key),
        )

    def cleanup_requests(self):
        return self._request("GET", "cleanup-requests")

    def retry_cleanup(self, request_id, reason, *, idempotency_key=None):
        return self._request(
            "POST",
            f"cleanup-requests/{_id(request_id)}/retry",
            json={"reason": reason},
            headers=_headers(idempotency_key),
        )

    def index_history(self, version_id):
        return self._request(
            "GET", f"document-versions/{_id(version_id)}/index/history"
        )

    def content_conflicts(self, version_id, *, page=0):
        return self._request(
            "GET",
            f"document-versions/{_id(version_id)}/content-conflicts",
            params={"page": page},
        )

    def chunk_changes(self, version_id, *, page=0):
        return self._request(
            "GET",
            f"document-versions/{_id(version_id)}/chunk-changes",
            params={"page": page},
        )

    def resolve_content_conflict(
        self,
        version_id,
        conflict_id,
        resolution: ConflictResolution,
        *,
        idempotency_key=None,
    ):
        return self._request(
            "POST",
            f"document-versions/{_id(version_id)}/content-conflicts/{_id(conflict_id)}/resolution",
            json=asdict(resolution),
            headers=_headers(idempotency_key),
        )

    def document_contexts(self, version_id, *, page=0):
        return self._request(
            "GET",
            f"document-versions/{_id(version_id)}/contexts",
            params={"page": page},
        )

    def document_quality(self, version_id, *, page=0):
        return self._request(
            "GET", f"document-versions/{_id(version_id)}/quality", params={"page": page}
        )

    def document_context(self, version_id, context_id):
        return self._request(
            "GET", f"document-versions/{_id(version_id)}/contexts/{_id(context_id)}"
        )

    def save_faq(
        self, version_id, faq: FaqInput, *, context_id=None, idempotency_key=None
    ):
        path = f"document-versions/{_id(version_id)}/faqs"
        if context_id is not None:
            path += "/" + _id(context_id)
        return self._request(
            "POST" if context_id is None else "PUT",
            path,
            json=asdict(faq),
            headers=_headers(idempotency_key),
        )

    def detach_context(
        self, version_id, context_id, revision, reason, *, idempotency_key=None
    ):
        return self._request(
            "POST",
            f"document-versions/{_id(version_id)}/contexts/{_id(context_id)}/detach",
            json={"revision": revision, "reason": reason},
            headers=_headers(idempotency_key),
        )

    def job(self, job_id):
        return self._request("GET", f"jobs/{_id(job_id)}")

    def index(self, version_id, *, idempotency_key=None):
        return self._request(
            "POST",
            f"document-versions/{_id(version_id)}/index",
            headers=_headers(idempotency_key),
        )

    def publish(
        self,
        document_id,
        version_id,
        revision,
        version_revision,
        *,
        idempotency_key=None,
    ):
        return self._request(
            "POST",
            f"documents/{_id(document_id)}/publications",
            json=dict(
                version_id=_id(version_id),
                revision=revision,
                version_revision=version_revision,
            ),
            headers=_headers(idempotency_key),
        )

    def search(self, query: Query, *, idempotency_key=None):
        return self._request(
            "POST",
            "retrieval/search",
            json=asdict(query),
            headers=_headers(idempotency_key),
        )

    @contextmanager
    def answer(self, query: Query, *, idempotency_key=None):
        """Use with; leaving the context closes the response, including early cancellation."""
        with self._http.stream(
            "POST",
            "answers",
            json=asdict(query),
            headers={**_headers(idempotency_key), "Accept": "text/event-stream"},
        ) as response:
            if not response.is_success:
                response.read()
            _check(response)
            _stream_type(response)

            def events():
                parser = _SSE()
                for line in response.iter_lines():
                    event = parser.line(line)
                    if event:
                        yield event
                    if parser.done:
                        return
                raise StreamError("Connection ended before done")

            yield events()


class AsyncClient:
    def __init__(self, base_url, token, *, timeout=120.0, transport=None):
        self._http = httpx.AsyncClient(**_options(base_url, token, timeout, transport))

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        await self.aclose()

    async def aclose(self):
        await self._http.aclose()

    async def _request(self, method, path, **kwargs):
        response = await self._http.request(method, path, **kwargs)
        _check(response)
        return response.json() if response.content else None

    async def create_conversation(self, *, application_id=None, knowledge_base_ids=()):
        return await self._request(
            "POST",
            "conversations",
            json=dict(
                application_id=application_id,
                knowledge_base_ids=list(knowledge_base_ids),
            ),
        )

    async def conversations(self):
        return await self._request("GET", "conversations")

    async def conversation(self, conversation_id):
        return await self._request("GET", f"conversations/{_id(conversation_id)}")

    async def answer_history(self, *, conversation_id=None):
        params = {"conversation_id": _id(conversation_id)} if conversation_id else {}
        return await self._request("GET", "answers", params=params)

    async def submit_feedback(
        self, answer_id, feedback, *, reason=None, comment="", revision=0
    ):
        return await self._request(
            "POST",
            f"answers/{_id(answer_id)}/feedback",
            json=dict(
                feedback=feedback, reason=reason, comment=comment, revision=revision
            ),
        )

    async def create_improvement(
        self, source_kind, source_id, knowledge_base_id, *, description=""
    ):
        return await self._request(
            "POST",
            "improvements",
            json=dict(
                source_kind=source_kind,
                source_id=_id(source_id),
                knowledge_base_id=_id(knowledge_base_id),
                description=description,
            ),
        )

    async def improvements(self):
        return await self._request("GET", "improvements")

    async def improvement(self, task_id):
        return await self._request("GET", f"improvements/{_id(task_id)}")

    async def improvement_assignees(self, task_id):
        return await self._request("GET", f"improvements/{_id(task_id)}/assignees")

    async def update_improvement(
        self, task_id, *, revision, state, assignee_id=None, resolution=""
    ):
        return await self._request(
            "PUT",
            f"improvements/{_id(task_id)}",
            json=dict(
                revision=revision,
                state=state,
                assignee_id=_id(assignee_id) if assignee_id else None,
                resolution=resolution,
            ),
        )

    async def citation_source(self, answer_id, citation_id):
        return await self._request(
            "GET", f"answers/{_id(answer_id)}/citations/{_id(citation_id)}"
        )

    async def saved_answer(self, answer_id):
        return await self._request("GET", f"answers/{_id(answer_id)}")

    async def operations_status(self):
        return await self._request("GET", "operations/status")

    async def knowledge_bases(self):
        return await self._request("GET", "knowledge-bases")

    async def create_knowledge_base(
        self, name, description="", *, idempotency_key=None
    ):
        return await self._request(
            "POST",
            "knowledge-bases",
            json=dict(name=name, description=description),
            headers=_headers(idempotency_key),
        )

    async def upload(self, knowledge_base_id, file, *, idempotency_key=None):
        path = Path(file)
        with path.open("rb") as source:
            return await self._request(
                "POST",
                f"knowledge-bases/{_id(knowledge_base_id)}/documents",
                files={"file": (path.name, source, "application/octet-stream")},
                headers=_headers(idempotency_key),
            )

    async def knowledge_configurations(self, knowledge_base_id):
        return await self._request(
            "GET", f"knowledge-bases/{_id(knowledge_base_id)}/configurations"
        )

    async def configuration_models(self, knowledge_base_id):
        return await self._request(
            "GET", f"knowledge-bases/{_id(knowledge_base_id)}/configuration-models"
        )

    async def create_knowledge_configuration(
        self,
        knowledge_base_id,
        configuration: KnowledgeConfiguration,
        *,
        idempotency_key=None,
    ):
        return await self._request(
            "POST",
            f"knowledge-bases/{_id(knowledge_base_id)}/configurations",
            json=asdict(configuration),
            headers=_headers(idempotency_key),
        )

    async def configuration_impact(self, knowledge_base_id, configuration_id):
        return await self._request(
            "GET",
            f"knowledge-bases/{_id(knowledge_base_id)}/configurations/{_id(configuration_id)}/impact",
        )

    async def publish_knowledge_configuration(
        self,
        knowledge_base_id,
        configuration_id,
        revision,
        reason,
        *,
        idempotency_key=None,
    ):
        return await self._request(
            "POST",
            f"knowledge-bases/{_id(knowledge_base_id)}/configuration-publications",
            json={
                "configuration_id": _id(configuration_id),
                "revision": revision,
                "reason": reason,
            },
            headers=_headers(idempotency_key),
        )

    async def bind_configuration(self, version_id, revision, *, idempotency_key=None):
        return await self._request(
            "POST",
            f"document-versions/{_id(version_id)}/configuration-binding",
            json={"revision": revision},
            headers=_headers(idempotency_key),
        )

    async def reprocess(self, version_id, *, idempotency_key=None):
        return await self._request(
            "POST",
            f"document-versions/{_id(version_id)}/reprocess",
            headers=_headers(idempotency_key),
        )

    async def document_versions(self, document_id):
        return await self._request("GET", f"documents/{_id(document_id)}/versions")

    async def document_chunks(self, version_id, *, page=0):
        return await self._request(
            "GET", f"document-versions/{_id(version_id)}/chunks", params={"page": page}
        )

    async def edit_chunk(self, chunk_id, edit: ChunkEdit):
        return await self._request("PUT", f"chunks/{_id(chunk_id)}", json=asdict(edit))

    async def chunk_operation(
        self, version_id, operation: ChunkOperation, *, idempotency_key=None
    ):
        return await self._request(
            "POST",
            f"document-versions/{_id(version_id)}/chunk-operations",
            json=asdict(operation),
            headers=_headers(idempotency_key),
        )

    async def rebuild_index(
        self, version_id, input: IndexRebuild, *, idempotency_key=None
    ):
        return await self._request(
            "POST",
            f"document-versions/{_id(version_id)}/index/rebuild",
            json=asdict(input),
            headers=_headers(idempotency_key),
        )

    async def check_index(self, version_id, *, idempotency_key=None):
        return await self._request(
            "POST",
            f"document-versions/{_id(version_id)}/index/checks",
            headers=_headers(idempotency_key),
        )

    async def cleanup_requests(self):
        return await self._request("GET", "cleanup-requests")

    async def retry_cleanup(self, request_id, reason, *, idempotency_key=None):
        return await self._request(
            "POST",
            f"cleanup-requests/{_id(request_id)}/retry",
            json={"reason": reason},
            headers=_headers(idempotency_key),
        )

    async def index_history(self, version_id):
        return await self._request(
            "GET", f"document-versions/{_id(version_id)}/index/history"
        )

    async def content_conflicts(self, version_id, *, page=0):
        return await self._request(
            "GET",
            f"document-versions/{_id(version_id)}/content-conflicts",
            params={"page": page},
        )

    async def chunk_changes(self, version_id, *, page=0):
        return await self._request(
            "GET",
            f"document-versions/{_id(version_id)}/chunk-changes",
            params={"page": page},
        )

    async def resolve_content_conflict(
        self,
        version_id,
        conflict_id,
        resolution: ConflictResolution,
        *,
        idempotency_key=None,
    ):
        return await self._request(
            "POST",
            f"document-versions/{_id(version_id)}/content-conflicts/{_id(conflict_id)}/resolution",
            json=asdict(resolution),
            headers=_headers(idempotency_key),
        )

    async def document_contexts(self, version_id, *, page=0):
        return await self._request(
            "GET",
            f"document-versions/{_id(version_id)}/contexts",
            params={"page": page},
        )

    async def document_quality(self, version_id, *, page=0):
        return await self._request(
            "GET", f"document-versions/{_id(version_id)}/quality", params={"page": page}
        )

    async def document_context(self, version_id, context_id):
        return await self._request(
            "GET", f"document-versions/{_id(version_id)}/contexts/{_id(context_id)}"
        )

    async def save_faq(
        self, version_id, faq: FaqInput, *, context_id=None, idempotency_key=None
    ):
        path = f"document-versions/{_id(version_id)}/faqs"
        if context_id is not None:
            path += "/" + _id(context_id)
        return await self._request(
            "POST" if context_id is None else "PUT",
            path,
            json=asdict(faq),
            headers=_headers(idempotency_key),
        )

    async def detach_context(
        self, version_id, context_id, revision, reason, *, idempotency_key=None
    ):
        return await self._request(
            "POST",
            f"document-versions/{_id(version_id)}/contexts/{_id(context_id)}/detach",
            json={"revision": revision, "reason": reason},
            headers=_headers(idempotency_key),
        )

    async def job(self, job_id):
        return await self._request("GET", f"jobs/{_id(job_id)}")

    async def index(self, version_id, *, idempotency_key=None):
        return await self._request(
            "POST",
            f"document-versions/{_id(version_id)}/index",
            headers=_headers(idempotency_key),
        )

    async def publish(
        self,
        document_id,
        version_id,
        revision,
        version_revision,
        *,
        idempotency_key=None,
    ):
        return await self._request(
            "POST",
            f"documents/{_id(document_id)}/publications",
            json=dict(
                version_id=_id(version_id),
                revision=revision,
                version_revision=version_revision,
            ),
            headers=_headers(idempotency_key),
        )

    async def search(self, query: Query, *, idempotency_key=None):
        return await self._request(
            "POST",
            "retrieval/search",
            json=asdict(query),
            headers=_headers(idempotency_key),
        )

    @asynccontextmanager
    async def answer(self, query: Query, *, idempotency_key=None):
        async with self._http.stream(
            "POST",
            "answers",
            json=asdict(query),
            headers={**_headers(idempotency_key), "Accept": "text/event-stream"},
        ) as response:
            if not response.is_success:
                await response.aread()
            _check(response)
            _stream_type(response)

            async def events():
                parser = _SSE()
                async for line in response.aiter_lines():
                    event = parser.line(line)
                    if event:
                        yield event
                    if parser.done:
                        return
                raise StreamError("Connection ended before done")

            yield events()
