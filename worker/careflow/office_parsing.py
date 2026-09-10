"""Office body-order extraction; coordinates refer to the original document."""

import re
from io import BytesIO

from careflow.parsing_types import Block, InvalidFile
from careflow.table_semantics import describe


def docx_blocks(data: bytes) -> list[Block]:
    from docx import Document
    from docx.text.paragraph import Paragraph

    document = Document(BytesIO(data))
    blocks = []
    headings = []
    caption = ""
    paragraph_no = table_no = 0
    for element in document.iter_inner_content():
        if isinstance(element, Paragraph):
            paragraph_no += 1
            style = element.style.name if element.style else ""
            caption = element.text if style == "Caption" else ""
            heading = re.fullmatch(r"Heading (\d+)", style)
            kind = "paragraph"
            if heading:
                depth = int(heading.group(1))
                headings = [(d, t) for d, t in headings if d < depth]
                headings.append((depth, element.text))
                kind = "heading"
            elif element._p.xpath("./w:pPr/w:numPr") or style.startswith("List"):
                kind = "list"
            if element.text.strip():
                blocks.append(
                    Block(
                        element.text,
                        {
                            "type": "paragraph",
                            "paragraph": paragraph_no,
                            "kind": kind,
                            "title_path": [t for _, t in headings],
                        },
                    )
                )
            continue
        table_no += 1
        if not element.rows:
            continue
        complex_table = bool(element._tbl.xpath(".//w:gridSpan|.//w:vMerge|.//w:tbl"))
        warning = "复杂或合并表格已展开，行列关系需人工核对" if complex_table else ""
        header = [cell.text.strip() for cell in element.rows[0].cells]
        if len(set(header)) != len(header) or any(not h for h in header):
            warning = "; ".join(filter(None, [warning, "表头为空或重复，请核对列含义"]))
        rows = list(element.rows[1:])
        # A one-row table still contains source information; do not silently discard it.
        if not rows:
            rows = list(element.rows)
            header = [f"列{i + 1}" for i in range(len(header))]
        for row_no, row in enumerate(rows, 2 if len(element.rows) > 1 else 1):
            blocks.append(
                Block(
                    " | ".join(
                        f"{header[i] if i < len(header) and header[i] else f'列{i + 1}'}: {cell.text}"
                        for i, cell in enumerate(row.cells)
                    ),
                    describe(
                        header,
                        [cell.text for cell in row.cells],
                        caption
                        or " > ".join(t for _, t in headings)
                        or f"表格 {table_no}",
                        {
                            "type": "table",
                            "table": table_no,
                            "row": row_no,
                            "columns": len(row.cells),
                            "column_start": 1,
                            "column_end": len(row.cells),
                            "title_path": [t for _, t in headings],
                            "headers": header,
                        },
                    ),
                    warning,
                )
            )
        caption = ""
    return blocks


def xlsx_blocks(data: bytes) -> list[Block]:
    from openpyxl import load_workbook
    from openpyxl.utils import get_column_letter

    # Formula expressions are retained as source, never calculated or executed here.
    workbook = load_workbook(
        BytesIO(data), read_only=True, data_only=False, keep_links=False
    )
    blocks = []
    try:
        for sheet in workbook:
            if sheet.max_column and sheet.max_column > 1000:
                raise InvalidFile("Table exceeds 1000 columns")
            rows = sheet.iter_rows()
            first = next(rows, ())
            header = [
                str(c.value) if c.value is not None else f"列{i + 1}"
                for i, c in enumerate(first)
            ]
            # Merged coordinates are read separately from bounded Office XML below.
            for row_no, row in enumerate(rows, 2):
                if row_no > 100_001:
                    raise InvalidFile("Table exceeds 100000 rows")
                nonempty = [(i, c) for i, c in enumerate(row) if c.value is not None]
                if not nonempty:
                    continue
                warning = "工作表按行展开，合并单元格及多行表头需人工核对"
                if any(c.data_type == "f" for _, c in nonempty):
                    warning += "; 公式仅保留表达式，未计算结果"
                blocks.append(
                    Block(
                        " | ".join(
                            f"{header[i] if i < len(header) else f'列{i + 1}'}: {c.value}"
                            for i, c in nonempty
                        ),
                        describe(
                            header,
                            [c.value for c in row],
                            sheet.title,
                            {
                                "type": "table",
                                "sheet": sheet.title,
                                "row": row_no,
                                "columns": len(row),
                                "column_start": 1,
                                "column_end": len(row),
                                "cell_range": f"A{row_no}:{get_column_letter(len(row))}{row_no}",
                                "headers": header,
                            },
                        ),
                        warning,
                    )
                )
    finally:
        workbook.close()
    return blocks
