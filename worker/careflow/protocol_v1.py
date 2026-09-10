"""Request/response schemas for /internal/v1; model text never confers authority."""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, FiniteFloat, model_validator

from careflow.model_configuration import ModelConfiguration


class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Candidate(Contract):
    id: str = Field(min_length=1, max_length=100)
    content: str = Field(min_length=1, max_length=10000)


class Hit(Contract):
    id: str = Field(min_length=1, max_length=100)
    score: FiniteFloat | None = None


class ConfiguredOperation(Contract):
    model_configuration: ModelConfiguration | None = None


class Recall(ConfiguredOperation):
    tenant_id: str = Field(min_length=1)
    version_ids: list[str] = Field(max_length=10000)
    generation_ids: list[str] | None = Field(
        default=None, min_length=1, max_length=10000
    )
    expected_model_identity: str | None = Field(
        default=None, min_length=1, max_length=500
    )
    query: str = Field(min_length=1, max_length=4000)
    mode: Literal["hybrid", "semantic", "keyword"] = "hybrid"
    allow_degraded: bool = False


class RecallResponse(Contract):
    dense: list[Hit] = Field(max_length=40)
    bm25: list[Hit] = Field(max_length=40)
    fused: list[Hit] = Field(max_length=40)
    degraded: bool
    warning: str | None = None


class Rerank(ConfiguredOperation):
    query: str = Field(min_length=1, max_length=4000)
    candidates: list[Candidate] = Field(max_length=40)
    allow_degraded: bool = False


class RerankResponse(Contract):
    results: list[Hit] = Field(max_length=40)
    degraded: bool
    warning: str | None = None


class Generate(ConfiguredOperation):
    query: str = Field(min_length=1, max_length=4000)
    evidence: list[Candidate] = Field(min_length=1, max_length=6)


class Tokenize(ConfiguredOperation):
    text: str = Field(min_length=1, max_length=10000)
    model_tokenizer: Literal["cl100k_base", "provider"] = "cl100k_base"
    model_maximum: int = Field(default=600, ge=1, le=131072)


class TokenizeResponse(Contract):
    token_count: int = Field(ge=0, le=100000)
    model_token_count: int = Field(ge=0, le=100000)
    model_limit: int = Field(ge=1, le=131072)


class GenerationEvent(Contract):
    text: str | None = None
    done: Literal[True] | None = None
    error: str | None = None

    @model_validator(mode="after")
    def exactly_one_event(self):
        if sum(value is not None for value in (self.text, self.done, self.error)) != 1:
            raise ValueError("Expected exactly one generation event")
        return self


class ParsingConfiguration(Contract):
    pdf_page_limit: int = Field(ge=1, le=500)


class ChunkingConfiguration(Contract):
    target: int = Field(ge=1, le=600)
    maximum: int = Field(ge=1, le=600)
    overlap: int = Field(ge=0, le=599)
    strategy: Literal["recursive", "token"] = "recursive"
    include_context: bool = True
    model_tokenizer: Literal["cl100k_base", "provider"] = "cl100k_base"
    model_maximum: int = Field(default=600, ge=1, le=131072)
    layout: Literal["standard", "parent_child", "faq"] = "standard"
    parent_maximum: int = Field(default=1600, ge=1, le=1600)

    @model_validator(mode="after")
    def bounded_overlap(self):
        if not self.overlap < self.target <= self.maximum:
            raise ValueError("Expected overlap < target <= maximum")
        return self


class RuntimeConfiguration(Contract):
    id: str = Field(min_length=1, max_length=36)
    parsing: ParsingConfiguration
    chunking: ChunkingConfiguration
    embedding: ModelConfiguration
    rerank: ModelConfiguration
    generation: ModelConfiguration

    @model_validator(mode="after")
    def model_kinds(self):
        if (
            self.embedding.kind != "EMBEDDING"
            or self.rerank.kind != "RERANK"
            or self.generation.kind != "GENERATION"
        ):
            raise ValueError("Model configuration kinds do not match task contract")
        return self


class TaskClaim(Contract):
    generation_id: str | None = None
    id: str
    lease_token: str
    kind: Literal["PARSE", "INDEX"]
    tenant_id: str
    version_id: str
    filename: str
    pdf_page_limit: int = Field(default=500, ge=1, le=500)
    configuration: RuntimeConfiguration | None = None


class ParsedChunk(Contract):
    source_text: str = Field(max_length=100000)
    content: str = Field(min_length=1, max_length=10000)
    location: str = Field(min_length=1, max_length=20000)
    token_count: int = Field(ge=1, le=600)
    context_ordinal: int | None = Field(default=None, ge=0, le=49999)


class ParsedContext(Contract):
    ordinal: int = Field(ge=0, le=49999)
    kind: Literal["PARENT", "FAQ"]
    source_text: str = Field(max_length=100000)
    content: str = Field(min_length=1, max_length=10000)
    location: str = Field(min_length=1, max_length=20000)
    token_count: int = Field(ge=1, le=1600)
    question: str | None = Field(default=None, max_length=1000)
    alternatives: list[str] = Field(default_factory=list, max_length=20)
    answer: str | None = Field(default=None, max_length=8000)


class ParseCompletion(Contract):
    chunks: list[ParsedChunk] = Field(min_length=1, max_length=50000)
    contexts: list[ParsedContext] = Field(default_factory=list, max_length=50000)


class ManualFaq(ConfiguredOperation):
    question: str = Field(min_length=1, max_length=1000)
    alternatives: list[str] = Field(default_factory=list, max_length=20)
    answer: str = Field(min_length=1, max_length=8000)
    chunking: ChunkingConfiguration


class IndexCompletion(Contract):
    generation_id: str
    manifest: str = Field(pattern=r"^[0-9a-f]{64}$")
    verified: Literal[True]
    model_identity: str = Field(min_length=1, max_length=500)
    embedding_tokens: int | None = Field(default=None, ge=0)
    indexed_chunks: int = Field(ge=1, le=50000)
    embedded_texts: int = Field(ge=0, le=50000)
    reused_chunks: int = Field(ge=0, le=50000)


class IndexEntry(Contract):
    id: str = Field(min_length=36, max_length=36)
    content_hash: str = Field(pattern=r"^[0-9a-f]{64}$")


class IndexVerification(ConfiguredOperation):
    tenant_id: str
    version_id: str
    generation_id: str | None = None
    expected_model_identity: str
    chunks: list[IndexEntry] = Field(max_length=50000)


class IndexVerificationResponse(Contract):
    consistent: bool
    expected_count: int = Field(ge=0, le=50000)
    actual_count: int = Field(ge=0, le=50000)
    missing_count: int = Field(ge=0, le=50000)
    extra_count: int = Field(ge=0, le=50000)
    mismatched_count: int = Field(ge=0, le=50000)
    manifest: str = Field(pattern=r"^[0-9a-f]{64}$")
    samples: dict[str, list[str]]
