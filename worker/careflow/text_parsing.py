"""Text structure preserves exact decoded offsets, including fenced code."""

import re
from collections import Counter
from dataclasses import replace

from careflow.parsing_types import Block


def markdown_blocks(text: str) -> list[Block]:
    headings = []
    fence = None
    offset = 0
    for line in text.splitlines(keepends=True):
        match = re.match(r"^ {0,3}(`{3,}|~{3,})", line)
        if match:
            marker = match.group(1)
            if fence is None:
                fence = marker
            elif marker[0] == fence[0] and len(marker) >= len(fence):
                fence = None
        elif fence is None:
            heading = re.match(r"^ {0,3}(#{1,6})[^\S\n]+([^\n]+)", line)
            if heading:
                headings.append(
                    (offset, len(heading.group(1)), heading.group(2).strip())
                )
        offset += len(line)
    starts = sorted({0, *(offset for offset, _, _ in headings)})
    by_start = {start: (depth, title) for start, depth, title in headings}
    path = []
    blocks = []
    for index, start in enumerate(starts):
        end = starts[index + 1] if index + 1 < len(starts) else len(text)
        if start in by_start:
            depth, title = by_start[start]
            path = [(d, t) for d, t in path if d < depth] + [(depth, title)]
        raw = text[start:end]
        blocks.append(
            Block(
                raw,
                {
                    "type": "text",
                    "start": start,
                    "end": end,
                    "title_path": [t for _, t in path],
                },
                "Markdown表格保留原始行列，请核对表头"
                if re.search(r"^\s*\|.*\|", raw, re.MULTILINE)
                else "",
            )
        )
    return blocks


def repeated_page_margins(blocks: list[Block]) -> list[Block]:
    """Remove only exact repeated first/last lines on at least three PDF pages.

    Source remains unchanged; ignored character ranges support before/after review.
    """
    pages = [b for b in blocks if b.location.get("type") == "pdf"]
    if len(pages) < 3:
        return blocks
    edges = {}
    counts = Counter()
    for block in pages:
        lines = list(re.finditer(r"[^\r\n]+", block.text))
        lines = [line for line in lines if line.group().strip()]
        selected = list(
            {(m.start(), m.end()): m for m in lines[:1] + lines[-1:]}.values()
        )
        edges[id(block)] = selected
        counts.update(
            {m.group().strip() for m in selected if len(m.group().strip()) <= 120}
        )
    threshold = max(3, (len(pages) * 3 + 4) // 5)
    result = []
    for block in blocks:
        spans = tuple(
            (m.start(), m.end())
            for m in edges.get(id(block), [])
            if counts[m.group().strip()] >= threshold
        )
        result.append(
            replace(
                block,
                ignored_spans=spans,
                warning="; ".join(
                    filter(
                        None,
                        [
                            block.warning,
                            "已清理重复页边文字，请对照原文" if spans else "",
                        ],
                    )
                ),
            )
        )
    return result


def clean_segment(block: Block, start: int, end: int) -> str:
    parts = []
    cursor = start
    for left, right in sorted(block.ignored_spans):
        if right <= start or left >= end:
            continue
        parts.append(block.text[cursor : max(cursor, left)])
        cursor = max(cursor, min(right, end))
    parts.append(block.text[cursor:end])
    text = "".join(parts).replace("\r\n", "\n").replace("\r", "\n")
    text = re.sub(r"[ \t]+", " ", text)
    return re.sub(r"\n{3,}", "\n\n", text).strip()
