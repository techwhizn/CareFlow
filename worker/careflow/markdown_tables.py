"""Recognise explicit pipe tables outside fences while retaining source offsets."""

import re

from careflow.parsing_types import Block
from careflow.table_semantics import describe


def cells(line):
    return [part.strip() for part in re.split(r"(?<!\\)\|", line.strip().strip("|"))]


def expand(block):
    lines = block.text.splitlines(keepends=True)
    offsets, offset = [], 0
    for line in lines:
        offsets.append(offset)
        offset += len(line)
    offsets.append(offset)
    output, cursor, index, table, fence = [], 0, 0, 0, None

    def prose(left, right):
        if right > left and block.text[left:right].strip():
            output.append(
                Block(
                    block.text[left:right],
                    {
                        **block.location,
                        "start": block.location.get("start", 0) + left,
                        "end": block.location.get("start", 0) + right,
                    },
                    block.warning,
                )
            )

    while index + 1 < len(lines):
        marker = re.match(r"^ {0,3}(`{3,}|~{3,})", lines[index])
        if marker:
            value = marker.group(1)
            if fence is None:
                fence = value
            elif value[0] == fence[0] and len(value) >= len(fence):
                fence = None
        header = cells(lines[index])
        separator = cells(lines[index + 1])
        if (
            fence
            or "|" not in lines[index]
            or not separator
            or not all(re.fullmatch(r":?-{3,}:?", value) for value in separator)
        ):
            index += 1
            continue
        end = index + 2
        while end < len(lines) and "|" in lines[end] and lines[end].strip():
            end += 1
        if end == index + 2:
            index += 2
            continue
        prose(cursor, offsets[index])
        table += 1
        name = (
            " > ".join(block.location.get("title_path", [])) or f"Markdown表格 {table}"
        )
        for row in range(index + 2, end):
            values = cells(lines[row])
            location = describe(
                header,
                values,
                name,
                {
                    **block.location,
                    "type": "table",
                    "table": table,
                    "start": block.location.get("start", 0) + offsets[row],
                    "end": block.location.get("start", 0) + offsets[row + 1],
                    "header_start": block.location.get("start", 0) + offsets[index],
                    "row": row - index,
                    "columns": len(values),
                    "column_start": 1,
                    "column_end": len(values),
                },
            )
            if len(separator) != len(header):
                location["quality_codes"].append("TABLE_ROW_BROKEN")
            output.append(
                Block(
                    lines[row],
                    location,
                    "Markdown表格按显式分隔符保留；内嵌代码和转义分隔符需核对",
                )
            )
        cursor, index = offsets[end], end
    prose(cursor, len(block.text))
    return output
