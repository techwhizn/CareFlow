"""Public knowledge configuration types; credentials never belong in this contract."""

from dataclasses import dataclass
from typing import Literal


@dataclass(frozen=True)
class ParsingConfiguration:
    pdf_page_limit: int = 500


@dataclass(frozen=True)
class ChunkingConfiguration:
    target: int = 200
    maximum: int = 300
    overlap: int = 30


@dataclass(frozen=True)
class RetrievalConfiguration:
    mode: Literal["hybrid", "semantic", "keyword"] = "hybrid"
    limit: int = 6
    minimum_rerank_score: float | None = None
    allow_degraded: bool = False


@dataclass(frozen=True)
class ModelReferences:
    embedding_profile_id: str
    rerank_profile_id: str
    generation_profile_id: str
    embedding_profile_revision: int = 0
    rerank_profile_revision: int = 0
    generation_profile_revision: int = 0


@dataclass(frozen=True)
class KnowledgeConfiguration:
    name: str
    models: ModelReferences
    parsing: ParsingConfiguration = ParsingConfiguration()
    chunking: ChunkingConfiguration = ChunkingConfiguration()
    retrieval: RetrievalConfiguration = RetrievalConfiguration()
