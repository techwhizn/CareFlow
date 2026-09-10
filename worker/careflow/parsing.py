"""Bounded, source-addressable parsing; parser exceptions are terminal failures."""

import csv
import re
import zipfile
from io import BytesIO, StringIO
from pathlib import PurePath

from careflow.chunking import chunk as chunk
from careflow.markdown_tables import expand as expand_markdown_tables
from careflow.office_parsing import docx_blocks, xlsx_blocks
from careflow.parsing_limits import Limits
from careflow.parsing_types import Block, InvalidFile
from careflow.table_semantics import describe
from careflow.text_parsing import markdown_blocks, repeated_page_margins


def _zip_guard(data: bytes, expected: str):
    if not data.startswith(b"PK"):
        raise InvalidFile("Invalid Office file signature")
    limits = Limits.environment()
    with zipfile.ZipFile(BytesIO(data)) as archive:
        if len(archive.infolist()) > limits.archive_entries:
            raise InvalidFile("Archive entry count exceeds configured limit")
        if expected not in archive.namelist():
            raise InvalidFile("File format does not match extension")
        if sum(x.file_size for x in archive.infolist()) > limits.archive_bytes:
            raise InvalidFile("Expanded archive exceeds configured limit")
        if any(x.flag_bits & 1 for x in archive.infolist()):
            raise InvalidFile("Encrypted archive is not supported")


def _ocr(image):
    import pytesseract

    limits = Limits.environment()
    if image.width * image.height > limits.image_pixels:
        raise InvalidFile("Image dimensions exceed OCR limit")
    return pytesseract.image_to_string(
        image, lang="chi_sim+eng", timeout=limits.ocr_seconds
    )


def parse(data: bytes, filename: str) -> list[Block]:
    if not data or len(data) > 50 * 1024 * 1024:
        raise InvalidFile("File is empty or exceeds 50 MiB")
    limits = Limits.environment()
    ext = PurePath(filename).suffix.lower()
    blocks = []
    if ext in {".txt", ".md", ".csv"}:
        try:
            text = data.decode("utf-8-sig")
        except UnicodeDecodeError as exc:
            raise InvalidFile("Text must be UTF-8") from exc
        if "\x00" in text:
            raise InvalidFile("Binary input disguised as text")
        if ext == ".csv":
            rows = csv.reader(StringIO(text))
            header = next(rows, [])
            for row_num, row in enumerate(rows, 2):
                if row_num > 100_001:
                    raise InvalidFile("Table exceeds 100000 rows")
                blocks.append(
                    Block(
                        " | ".join(
                            f"{header[i] if i < len(header) else i + 1}: {value}"
                            for i, value in enumerate(row)
                        ),
                        describe(
                            header,
                            row,
                            PurePath(filename).name,
                            {
                                "type": "table",
                                "sheet": "CSV",
                                "row": row_num,
                                "columns": len(row),
                                "column_start": 1,
                                "column_end": len(row),
                                "headers": header,
                            },
                        ),
                        "列数与表头不一致，请核对" if len(row) != len(header) else "",
                    )
                )
        elif ext == ".md":
            for block in markdown_blocks(text):
                blocks.extend(expand_markdown_tables(block))
        else:
            for match in re.finditer(r"[^\n]+(?:\n(?!\n)[^\n]+)*", text):
                blocks.append(
                    Block(
                        match.group(),
                        {"type": "text", "start": match.start(), "end": match.end()},
                    )
                )
    elif ext == ".docx":
        _zip_guard(data, "word/document.xml")
        blocks.extend(docx_blocks(data))
    elif ext == ".xlsx":
        _zip_guard(data, "xl/workbook.xml")
        blocks.extend(xlsx_blocks(data))
    elif ext == ".pdf":
        if not data.startswith(b"%PDF-"):
            raise InvalidFile("Invalid PDF signature")
        from pypdf import PdfReader

        pdf = PdfReader(BytesIO(data))
        if pdf.is_encrypted or len(pdf.pages) > limits.pdf_pages:
            raise InvalidFile("Encrypted PDF or PDF exceeds configured page limit")
        rendered = None
        try:
            for i, page in enumerate(pdf.pages):
                text = page.extract_text(extraction_mode="layout") or ""
                warning = "PDF布局文本保留基础列间距；复杂表格与阅读顺序需人工核对"
                if len(text.strip()) < 30:
                    import pypdfium2

                    if rendered is None:
                        rendered = pypdfium2.PdfDocument(data)
                    rendered_page = rendered[i]
                    width, height = rendered_page.get_size()
                    if width * height * 1.5 * 1.5 > limits.image_pixels:
                        rendered_page.close()
                        raise InvalidFile("Rendered PDF page exceeds pixel limit")
                    bitmap = rendered_page.render(scale=1.5)
                    image = bitmap.to_pil()
                    try:
                        text = _ocr(image)
                    finally:
                        image.close()
                        bitmap.close()
                        rendered_page.close()
                    warning = "OCR 结果需人工核对"
                blocks.append(Block(text, {"type": "pdf", "page": i + 1}, warning))
        finally:
            if rendered is not None:
                rendered.close()
    elif ext in {".png", ".jpg", ".jpeg"}:
        from PIL import Image

        with Image.open(BytesIO(data)) as image:
            expected = "PNG" if ext == ".png" else "JPEG"
            if image.format != expected:
                raise InvalidFile("Image format does not match extension")
            blocks.append(
                Block(_ocr(image), {"type": "image", "page": 1}, "OCR 结果需人工核对")
            )
    else:
        raise InvalidFile("Unsupported file type")
    blocks = [b for b in blocks if b.text.strip()]
    if not blocks:
        raise InvalidFile("No readable content; inspect OCR or document structure")
    if sum(len(b.text) for b in blocks) > 10_000_000:
        raise InvalidFile("Extracted text exceeds processing limit")
    return repeated_page_margins(blocks)
