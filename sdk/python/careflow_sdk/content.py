"""Human-authored FAQ mutations use the reviewed content-version revision."""

from dataclasses import dataclass


@dataclass(frozen=True)
class FaqInput:
    revision: int
    question: str
    answer: str
    reason: str
    alternatives: tuple[str, ...] = ()
