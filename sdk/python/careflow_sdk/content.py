"""Human-authored FAQ mutations use the reviewed content-version revision."""

from dataclasses import dataclass
from typing import Literal


@dataclass(frozen=True)
class FaqInput:
    revision: int
    question: str
    answer: str
    reason: str
    alternatives: tuple[str, ...] = ()


@dataclass(frozen=True)
class ChunkRef:
    id: str
    revision: int


@dataclass(frozen=True)
class ChunkOperation:
    revision: int
    action: Literal["SPLIT", "MERGE", "SET_ENABLED", "TAGS"]
    chunks: tuple[ChunkRef, ...]
    reason: str
    split_offsets: tuple[int, ...] | None = None
    enabled: bool | None = None
    tags: tuple[str, ...] | None = None


@dataclass(frozen=True)
class ConflictResolution:
    revision: int
    action: Literal["KEEP_NEW", "APPEND_OLD", "APPLY_TO_CHUNK"]
    reason: str
    target: ChunkRef | None = None


@dataclass(frozen=True)
class ChunkEdit:
    content: str
    enabled: bool
    revision: int
    reason: str


def utf16_offset(text: str, character_offset: int) -> int:
    """Convert a Python character boundary to the public split-offset representation."""
    if not 0 <= character_offset <= len(text):
        raise ValueError("Character offset is outside the content")
    return len(text[:character_offset].encode("utf-16-le")) // 2
