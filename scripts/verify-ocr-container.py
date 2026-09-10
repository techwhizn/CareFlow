"""Run against explicit synthetic fixtures inside the built Linux Worker container."""

import json
import os
from pathlib import Path
import sys
import subprocess
from io import BytesIO
from zipfile import ZipFile

from careflow.isolated_parser import ParseFailure, parse_document


def rejected(data, filename, environment, allowed):
    previous = {key: os.environ.get(key) for key in environment}
    try:
        os.environ.update(environment)
        try:
            parse_document(data, filename)
        except ParseFailure as error:
            assert error.code in allowed, error.code
            print("PASS resource rejection", filename, error.code, flush=True)
        else:
            raise AssertionError("Resource limit unexpectedly allowed parsing")
    finally:
        for key, value in previous.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value


def main():
    import pytesseract
    from pypdf import PdfWriter

    assert sys.platform == "linux", "This check requires Linux"
    assert os.getuid() != 0, "Run parser as a nonroot user"
    assert {"chi_sim", "eng"} <= set(pytesseract.get_languages())
    root = Path(sys.argv[1])
    for name in ("synthetic-ocr.png", "synthetic-ocr.jpg", "synthetic-scan.pdf"):
        chunks = parse_document((root / name).read_bytes(), name)
        text = " ".join(chunk["content"] for chunk in chunks).lower()
        assert "careflow" in text and "cf-100" in text, name
        assert any(
            "OCR" in json.loads(chunk["location"])["warning"] for chunk in chunks
        )
        print("PASS", name, "recognized with source warning", flush=True)
    if (root / "synthetic-chinese.png").exists():
        chunks = parse_document(
            (root / "synthetic-chinese.png").read_bytes(), "synthetic-chinese.png"
        )
        text = "".join("".join(chunk["content"].split()) for chunk in chunks)
        assert "知识库" in text, "Chinese OCR mismatch"
        print("PASS Chinese OCR", flush=True)
    rejected(
        (root / "synthetic-ocr.png").read_bytes(),
        "image.png",
        {"PARSE_MAX_IMAGE_PIXELS": "100"},
        {"INVALID_FILE"},
    )
    pdf = PdfWriter()
    for _ in range(2):
        pdf.add_blank_page(width=100, height=100)
    output = BytesIO()
    pdf.write(output)
    rejected(
        output.getvalue(), "pages.pdf", {"PARSE_MAX_PDF_PAGES": "1"}, {"INVALID_FILE"}
    )
    archive = BytesIO()
    with ZipFile(archive, "w") as file:
        file.writestr("word/document.xml", "x" * 100)
    rejected(
        archive.getvalue(),
        "expanded.docx",
        {"PARSE_MAX_ARCHIVE_BYTES": "10"},
        {"INVALID_FILE"},
    )
    rejected(
        b"synthetic long text " * 400000,
        "timeout.txt",
        {"PARSE_TIMEOUT_SECONDS": "1"},
        {"PARSE_TIMEOUT", "PARSE_RESOURCE_LIMIT"},
    )
    environment = {
        **os.environ,
        "PARSE_MEMORY_MIB": "128",
        "PARSE_TIMEOUT_SECONDS": "1",
    }
    memory_probe = subprocess.run(
        [
            sys.executable,
            "-c",
            "from careflow.isolated_parser import _limit_process; _limit_process();\ntry: bytearray(256*1024*1024)\nexcept MemoryError: print('MEMORY_BLOCKED')",
        ],
        env=environment,
        capture_output=True,
        text=True,
        timeout=5,
    )
    assert (
        memory_probe.returncode == 0 and memory_probe.stdout.strip() == "MEMORY_BLOCKED"
    )
    cpu_probe = subprocess.run(
        [
            sys.executable,
            "-c",
            "from careflow.isolated_parser import _limit_process; _limit_process();\nwhile True: pass",
        ],
        env=environment,
        capture_output=True,
        timeout=5,
    )
    assert cpu_probe.returncode in (-9, -24), cpu_probe.returncode
    print("PASS Linux page/pixel/archive/time/memory limits", flush=True)


if __name__ == "__main__":
    main()
