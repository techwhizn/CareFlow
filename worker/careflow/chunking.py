"""Structure-aware slicing with exact source spans and separate model input budgets."""

import json
import re
from functools import cache

import tiktoken

from careflow import models
from careflow.parsing_types import Block, InvalidFile
from careflow.text_parsing import clean_segment


@cache
def encoding():
    return tiktoken.get_encoding("cl100k_base")


def tokens(text):
    return len(encoding().encode(text, disallowed_special=()))


def context(block, include_context):
    if not include_context:
        return ""
    title = " > ".join(block.location.get("title_path", []))
    if block.location.get("type") == "table":
        labels = block.location.get("headers", [])
        table = (
            block.location.get("table_name")
            or block.location.get("sheet")
            or block.location.get("table", "")
        )
        return "\n".join(
            filter(None, [title, f"表格 {table}" if table else "", " | ".join(labels)])
        )
    return title


def render(block, start, end, prefix):
    raw = block.text[start:end]
    cleaned = clean_segment(block, start, end)
    if not cleaned:
        return None
    content = (prefix + "\n" if prefix else "") + cleaned
    location = {
        **block.location,
        "block_start": start,
        "block_end": end,
        "block_length": block.location.get("block_length", len(block.text)),
        "context_prefix": prefix,
        "cleaning": {
            "whitespace_normalized": cleaned != raw,
            "ignored_spans": block.ignored_spans,
        },
        "warning": block.warning
        or ("切片较短，请检查上下文是否完整" if tokens(content) < 20 else ""),
    }
    return {
        "source_text": raw,
        "content": content,
        "token_count": tokens(content),
        "location": json.dumps(location, ensure_ascii=False),
    }


def boundary(text, start, end, strategy):
    if strategy == "token":
        return end
    # Keep the strongest available structural boundary in the latter half of the budget.
    for pattern in [
        r"\n\s*\n",
        r"\n",
        r"[。！？!?](?:\s|$)?|\.\s",
        r"[；;，,]\s*",
        r"\s+",
    ]:
        choices = [
            m.end()
            for m in re.finditer(pattern, text[start:end])
            if m.end() >= (end - start) // 2
        ]
        if choices:
            return start + choices[-1]
    return end


def split_block(block, target, maximum, overlap, strategy, include_context):
    prefix = context(block, include_context)
    if prefix and tokens(prefix + "\n") >= maximum:
        raise InvalidFile("Heading or table header exceeds chunk token budget")
    if prefix and tokens(prefix + "\n") >= target:
        target = maximum
    output, start = [], 0
    while start < len(block.text):
        lo, hi = start + 1, min(len(block.text), start + 32000)
        # Operate on Unicode code points, never decode partial tokenizer bytes.
        while lo < hi:
            mid = (lo + hi + 1) // 2
            cleaned = clean_segment(block, start, mid)
            content = (prefix + "\n" if prefix else "") + cleaned
            if not cleaned or tokens(content) <= target:
                lo = mid
            else:
                hi = mid - 1
        end = boundary(block.text, start, lo, strategy) if lo < len(block.text) else lo
        candidate = render(block, start, end, prefix)
        # BPE length is not strictly monotone; enforce the actual complete input after splitting.
        while candidate and candidate["token_count"] > maximum and end > start + 1:
            end -= 1
            candidate = render(block, start, end, prefix)
        if candidate:
            if candidate["token_count"] > maximum:
                raise InvalidFile(
                    "A source character with context exceeds token budget"
                )
            output.append(candidate)
        if end == len(block.text):
            break
        next_start = end
        while (
            next_start > start + 1
            and tokens(block.text[next_start - 1 : end]) <= overlap
        ):
            next_start -= 1
        start = max(start + 1, next_start)
    return output


def fit_model(chunks, model_tokenizer, model_maximum, maximum):
    """Check in bounded batches; oversize chunks split their body without discarding source."""
    prefixes = sorted(
        {
            json.loads(row["location"])["context_prefix"] + "\n"
            for row in chunks
            if json.loads(row["location"])["context_prefix"]
        }
    )
    if prefixes:
        counts, actual_limit = models.input_tokens(prefixes, model_tokenizer)
        if any(count >= min(model_maximum, actual_limit) for count in counts):
            raise InvalidFile("Source context exceeds model input window")
    pending = chunks
    accepted = []
    while pending:
        counts, actual_limit = models.input_tokens(
            [row["content"] for row in pending], model_tokenizer
        )
        limit = min(model_maximum, actual_limit)
        retry = []
        for row, count in zip(pending, counts, strict=True):
            if count <= limit and row["token_count"] <= maximum:
                location = json.loads(row["location"])
                location["model_token_count"] = count
                location["model_tokenizer"] = model_tokenizer
                accepted.append(
                    {**row, "location": json.dumps(location, ensure_ascii=False)}
                )
                continue
            location = json.loads(row["location"])
            raw = row["source_text"]
            if len(raw) <= 1:
                raise InvalidFile("Source context exceeds model input window")
            cut = boundary(raw, 0, max(1, len(raw) // 2), "recursive")
            for left, right in [(0, cut), (cut, len(raw))]:
                # Restore exact block-relative locations, including cleaning spans.
                base = location["block_start"]
                local_ignored = tuple(
                    (a - base, b - base)
                    for a, b in location["cleaning"]["ignored_spans"]
                )
                block = Block(raw, location, location.get("warning", ""), local_ignored)
                part = render(block, left, right, location["context_prefix"])
                if part:
                    adjusted = json.loads(part["location"])
                    adjusted.update(block_start=base + left, block_end=base + right)
                    adjusted["cleaning"]["ignored_spans"] = location["cleaning"][
                        "ignored_spans"
                    ]
                    part["location"] = json.dumps(adjusted, ensure_ascii=False)
                    retry.append(part)
        pending = retry
    # Stable order follows the input block and original offsets, not completion order.
    return accepted


def chunk(
    blocks,
    target=400,
    maximum=600,
    overlap=60,
    strategy="recursive",
    include_context=True,
    model_tokenizer="cl100k_base",
    model_maximum=600,
):
    if not 0 <= overlap < target <= maximum <= 600 or not 1 <= model_maximum <= 131072:
        raise ValueError("Invalid token configuration")
    if strategy not in {"recursive", "token"} or model_tokenizer not in {
        "cl100k_base",
        "provider",
    }:
        raise ValueError("Unknown chunking strategy or tokenizer")
    output = []
    for ordinal, block in enumerate(blocks):
        rows = split_block(block, target, maximum, overlap, strategy, include_context)
        for row in rows:
            location = json.loads(row["location"])
            location["block_ordinal"] = ordinal
            row["location"] = json.dumps(location, ensure_ascii=False)
        output.extend(rows)
    output = fit_model(output, model_tokenizer, model_maximum, maximum)

    def position(row):
        location = json.loads(row["location"])
        return location["block_ordinal"], location["block_start"], location["block_end"]

    output.sort(key=position)
    return output
