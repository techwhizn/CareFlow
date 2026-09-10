"""Source blocks shared by format adapters and chunking."""

from dataclasses import dataclass


class InvalidFile(ValueError):
    pass


@dataclass(frozen=True)
class Block:
    text: str
    location: dict
    warning: str = ""
    ignored_spans: tuple[tuple[int, int], ...] = ()
