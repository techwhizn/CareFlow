"""Public response shapes. Dictionaries and unknown added fields remain backward compatible."""

from typing import Any, TypedDict


class Entity(TypedDict):
    id: str


class KnowledgeBase(Entity, total=False):
    name: str
    description: str
    status: str
    revision: int
    configuration_revision: int
    owner_id: str
    language: str
    tags: list[str]


class Document(Entity, total=False):
    kb_id: str
    title: str
    status: str
    published_version: str | None
    revision: int
    metadata_revision: int


class DocumentVersion(Entity, total=False):
    document_id: str
    filename: str
    state: str
    revision: int
    configuration_id: str | None
    active_index_generation: str | None


class Chunk(Entity, total=False):
    version_id: str
    document_id: str
    ordinal_no: int
    content: str
    source_text: str
    location: str
    token_count: int
    enabled: bool
    revision: int
    covered_chunk_ids: list[str]


class Job(Entity, total=False):
    version_id: str
    kind: str
    state: str
    error_code: str | None
    http_request_id: str | None
    index_usage: dict[str, Any]
    ocr_usage: dict[str, int]


class UploadResult(TypedDict):
    document_id: str
    version_id: str
    job_id: str


class SearchResult(TypedDict):
    query_record_id: str
    trace_id: str
    evidence: list[Chunk]
    evidence_status: str
    evidence_tokens: int
    evidence_token_limit: int
    evidence_tokenizer: str
    configuration_id: str
    publication_versions: list[str]
    application_revision: int
    application_configuration_id: str
    degraded: bool


class Me(TypedDict):
    tenant_id: str
    subject_id: str
    role: str
    tenant: dict[str, str]
