"""Request/response schemas for /internal/v1; model text never confers authority."""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, FiniteFloat, model_validator


class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Candidate(Contract):
    id: str = Field(min_length=1, max_length=100)
    content: str = Field(min_length=1, max_length=10000)


class Hit(Contract):
    id: str = Field(min_length=1, max_length=100)
    score: FiniteFloat | None = None


class Recall(Contract):
    tenant_id: str = Field(min_length=1)
    version_ids: list[str] = Field(max_length=10000)
    query: str = Field(min_length=1, max_length=4000)
    mode: Literal["hybrid", "semantic", "keyword"] = "hybrid"
    allow_degraded: bool = False


class RecallResponse(Contract):
    dense: list[Hit] = Field(max_length=40)
    bm25: list[Hit] = Field(max_length=40)
    fused: list[Hit] = Field(max_length=40)
    degraded: bool
    warning: str | None = None


class Rerank(Contract):
    query: str = Field(min_length=1, max_length=4000)
    candidates: list[Candidate] = Field(max_length=40)
    allow_degraded: bool = False


class RerankResponse(Contract):
    results: list[Hit] = Field(max_length=40)
    degraded: bool
    warning: str | None = None


class Generate(Contract):
    query: str = Field(min_length=1, max_length=4000)
    evidence: list[Candidate] = Field(min_length=1, max_length=6)


class Tokenize(Contract):
    text: str = Field(min_length=1, max_length=10000)


class TokenizeResponse(Contract):
    token_count: int = Field(ge=0, le=100000)


class GenerationEvent(Contract):
    text: str | None = None
    done: Literal[True] | None = None
    error: str | None = None

    @model_validator(mode="after")
    def exactly_one_event(self):
        if sum(value is not None for value in (self.text, self.done, self.error)) != 1:
            raise ValueError("Expected exactly one generation event")
        return self


class TaskClaim(Contract):
    id: str
    lease_token: str
    kind: Literal["PARSE", "INDEX"]
    tenant_id: str
    version_id: str
    filename: str


class ParsedChunk(Contract):
    source_text: str = Field(max_length=100000)
    content: str = Field(min_length=1, max_length=10000)
    location: str = Field(min_length=1, max_length=20000)
    token_count: int = Field(ge=1, le=600)


class ParseCompletion(Contract):
    chunks: list[ParsedChunk] = Field(min_length=1, max_length=50000)


class IndexCompletion(Contract):
    verified: Literal[True]
    model_identity: str = Field(min_length=1, max_length=500)
    embedding_tokens: int | None = Field(default=None, ge=0)
