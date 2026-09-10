import multiprocessing
from io import BytesIO
from zipfile import ZipFile

import pytest

from careflow.isolated_parser import ParseFailure, parse_document
from careflow.parsing import InvalidFile, parse
from careflow.parsing_limits import Limits


def test_limits_can_only_be_tightened(monkeypatch):
    monkeypatch.setenv("PARSE_MAX_PDF_PAGES", "501")
    with pytest.raises(InvalidFile):
        Limits.environment()
    monkeypatch.setenv("PARSE_MAX_PDF_PAGES", "2")
    assert Limits.environment().pdf_pages == 2


def test_page_limit_rejects_before_render(monkeypatch):
    from pypdf import PdfWriter

    writer = PdfWriter()
    for _ in range(2):
        writer.add_blank_page(width=100, height=100)
    stream = BytesIO()
    writer.write(stream)
    monkeypatch.setenv("PARSE_MAX_PDF_PAGES", "1")
    with pytest.raises(InvalidFile, match="page limit"):
        parse(stream.getvalue(), "two.pdf")


def test_archive_limits_reject_before_office_parser(monkeypatch):
    stream = BytesIO()
    with ZipFile(stream, "w") as archive:
        archive.writestr("word/document.xml", b"x" * 100)
        archive.writestr("other", b"x")
    monkeypatch.setenv("PARSE_MAX_ARCHIVE_ENTRIES", "1")
    with pytest.raises(InvalidFile, match="entry count"):
        parse(stream.getvalue(), "archive.docx")
    monkeypatch.setenv("PARSE_MAX_ARCHIVE_ENTRIES", "10")
    monkeypatch.setenv("PARSE_MAX_ARCHIVE_BYTES", "50")
    with pytest.raises(InvalidFile, match="Expanded archive"):
        parse(stream.getvalue(), "archive.docx")


def test_image_pixel_limit_rejects_before_ocr(monkeypatch):
    from PIL import Image

    stream = BytesIO()
    Image.new("RGB", (100, 100), "white").save(stream, format="PNG")
    monkeypatch.setenv("PARSE_MAX_IMAGE_PIXELS", "100")
    with pytest.raises(InvalidFile, match="dimensions"):
        parse(stream.getvalue(), "image.png")


def test_isolated_real_parse_and_invalid_input_have_safe_errors():
    result = parse_document(b"Synthetic isolated parse fixture.", "fixture.txt")
    assert result[0]["content"] == "Synthetic isolated parse fixture."
    with pytest.raises(ParseFailure) as error:
        parse_document(b"secret invalid file", "fixture.exe")
    assert error.value.code == "INVALID_FILE"
    assert "secret" not in str(error.value)


def test_timeout_reaps_child(monkeypatch):
    before = {p.pid for p in multiprocessing.active_children()}
    monkeypatch.setenv("PARSE_TIMEOUT_SECONDS", "1")
    with pytest.raises(ParseFailure) as error:
        parse_document(b"long synthetic text " * 500000, "long.txt")
    assert error.value.code in {"PARSE_TIMEOUT", "PARSE_RESOURCE_LIMIT"}
    assert {p.pid for p in multiprocessing.active_children()} == before
