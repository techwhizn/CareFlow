"""Application configuration versions contain references, never API keys."""

from dataclasses import dataclass
from typing import Literal

from .configuration import RetrievalConfiguration


@dataclass(frozen=True)
class ApplicationModels:
    rerank_profile_id: str
    generation_profile_id: str
    rerank_profile_revision: int = 0
    generation_profile_revision: int = 0


@dataclass(frozen=True)
class AnswerPolicy:
    language: Literal["auto", "zh", "en"] = "auto"
    style: Literal["concise", "standard", "detailed"] = "standard"
    maximum_output_tokens: int = 2048
    history_rounds: int = 6
    history_tokens: int = 3000


@dataclass(frozen=True)
class ApplicationConfiguration:
    knowledge_base_ids: tuple[str, ...]
    revision: int
    allow_degraded: bool = False
    owner_id: str | None = None
    retrieval: RetrievalConfiguration | None = None
    models: ApplicationModels | None = None
    answer_policy: AnswerPolicy = AnswerPolicy()
