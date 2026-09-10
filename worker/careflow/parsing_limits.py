"""Deployment limits may be tightened; callers cannot enlarge resource budgets."""

import os
from dataclasses import dataclass

from careflow.parsing_types import InvalidFile


def bounded(name: str, maximum: int) -> int:
    try:
        value = int(os.environ.get(name, str(maximum)))
    except ValueError as exc:
        raise InvalidFile("Invalid parser limit configuration") from exc
    if not 1 <= value <= maximum:
        raise InvalidFile("Parser limits must be positive and cannot exceed defaults")
    return value


@dataclass(frozen=True)
class Limits:
    pdf_pages: int
    image_pixels: int
    archive_bytes: int
    archive_entries: int
    ocr_seconds: int
    parse_seconds: int

    @classmethod
    def environment(cls):
        return cls(
            bounded("PARSE_MAX_PDF_PAGES", 500),
            bounded("PARSE_MAX_IMAGE_PIXELS", 40_000_000),
            bounded("PARSE_MAX_ARCHIVE_BYTES", 200 * 1024 * 1024),
            bounded("PARSE_MAX_ARCHIVE_ENTRIES", 10000),
            bounded("OCR_TIMEOUT_SECONDS", 60),
            bounded("PARSE_TIMEOUT_SECONDS", 600),
        )
