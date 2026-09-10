"""Explicit parent and FAQ strategies. Relationships are document-local ordinals, never IDs."""

import json
import re

from careflow.chunking import chunk, split_block, tokens
from careflow.parsing_types import Block, InvalidFile


def context_row(
    row, ordinal, kind="PARENT", question=None, alternatives=None, answer=None
):
    return {
        **row,
        "ordinal": ordinal,
        "kind": kind,
        "question": question,
        "alternatives": alternatives or [],
        "answer": answer,
    }


def attach_children(block, start, end, settings, context_ordinal):
    ignored = tuple((a - start, b - start) for a, b in block.ignored_spans)
    source = Block(block.text[start:end], block.location, block.warning, ignored)
    children = chunk([source], **settings)
    for child in children:
        location = json.loads(child["location"])
        location["block_start"] += start
        location["block_end"] += start
        location["block_length"] = len(block.text)
        location["cleaning"]["ignored_spans"] = block.ignored_spans
        child["location"] = json.dumps(location, ensure_ascii=False)
        child["context_ordinal"] = context_ordinal
    return children


def parent_children(blocks, settings, parent_maximum):
    contexts, children = [], []
    for block in blocks:
        parents = split_block(
            block,
            parent_maximum,
            parent_maximum,
            0,
            "recursive",
            settings.get("include_context", True),
        )
        for parent in parents:
            ordinal = len(contexts)
            location = json.loads(parent["location"])
            contexts.append(context_row(parent, ordinal))
            children.extend(
                attach_children(
                    block,
                    location["block_start"],
                    location["block_end"],
                    settings,
                    ordinal,
                )
            )
    return {"chunks": children, "contexts": contexts}


QUESTION = re.compile(
    r"^(?:Q|Question|问|问题)\s*[:：]\s*(.+)$", re.MULTILINE | re.IGNORECASE
)
ANSWER = re.compile(
    r"^(?:A|Answer|答|答案)\s*[:：][ \t]*", re.MULTILINE | re.IGNORECASE
)
ALIASES = re.compile(
    r"^(?:Similar|Aliases|相似问法)\s*[:：]\s*(.+)$", re.MULTILINE | re.IGNORECASE
)


def faq_children(blocks, settings, parent_maximum):
    contexts, children = [], []
    for block in blocks:
        questions = list(QUESTION.finditer(block.text))
        prefix = block.text[: questions[0].start()] if questions else block.text
        if any(
            line.strip() and not line.lstrip().startswith("#")
            for line in prefix.splitlines()
        ):
            raise InvalidFile(
                "FAQ source requires explicitly labelled questions and answers"
            )
        for index, question_match in enumerate(questions):
            end = (
                questions[index + 1].start()
                if index + 1 < len(questions)
                else len(block.text)
            )
            answer_match = ANSWER.search(block.text, question_match.end(), end)
            if answer_match is None or not block.text[answer_match.end() : end].strip():
                raise InvalidFile("FAQ answer is missing")
            question = question_match.group(1).strip()
            between = block.text[question_match.end() : answer_match.start()]
            if any(
                line.strip() and ALIASES.fullmatch(line) is None
                for line in between.splitlines()
            ):
                raise InvalidFile("Unlabelled content between FAQ question and answer")
            alias_matches = ALIASES.findall(
                block.text[question_match.end() : answer_match.start()]
            )
            alternatives = [
                item.strip()
                for line in alias_matches
                for item in re.split(r"[|｜]", line)
                if item.strip()
            ]
            if (
                len(question) > 1000
                or len(alternatives) > 20
                or any(len(item) > 400 for item in alternatives)
            ):
                raise InvalidFile("FAQ question or alternative exceeds limit")
            answer = block.text[answer_match.end() : end].strip()
            content = (
                "问题："
                + question
                + "\n"
                + (
                    "相似问法：" + " | ".join(alternatives) + "\n"
                    if alternatives
                    else ""
                )
                + "答案："
                + answer
            )
            if tokens(content) > parent_maximum:
                raise InvalidFile("FAQ group exceeds configured parent context budget")
            ordinal = len(contexts)
            location = {
                **block.location,
                "block_start": question_match.start(),
                "block_end": end,
                "warning": block.warning,
            }
            contexts.append(
                context_row(
                    {
                        "source_text": block.text[question_match.start() : end],
                        "content": content,
                        "location": json.dumps(location, ensure_ascii=False),
                        "token_count": tokens(content),
                    },
                    ordinal,
                    "FAQ",
                    question,
                    alternatives,
                    answer,
                )
            )
            answer_block = Block(
                block.text,
                {
                    **block.location,
                    "title_path": [
                        *block.location.get("title_path", []),
                        question,
                        *alternatives,
                    ],
                },
                block.warning,
                block.ignored_spans,
            )
            children.extend(
                attach_children(
                    answer_block,
                    answer_match.end(),
                    end,
                    {**settings, "include_context": True},
                    ordinal,
                )
            )
    if not contexts:
        raise InvalidFile("No explicitly labelled FAQ pairs found")
    return {"chunks": children, "contexts": contexts}


def process_blocks(blocks, configuration=None):
    settings = dict(configuration or {})
    layout = settings.pop("layout", "standard")
    parent_maximum = settings.pop("parent_maximum", 1600)
    if not 1 <= parent_maximum <= 1600:
        raise ValueError("Invalid parent context limit")
    if layout == "standard":
        return {"chunks": chunk(blocks, **settings), "contexts": []}
    if layout == "parent_child":
        return parent_children(blocks, settings, parent_maximum)
    if layout == "faq":
        return faq_children(blocks, settings, parent_maximum)
    raise ValueError("Unknown context strategy")


def manual_faq(question, alternatives, answer, configuration):
    settings = dict(configuration)
    parent_maximum = settings.pop("parent_maximum", 1600)
    settings.pop("layout", None)
    content = (
        "问题："
        + question
        + "\n"
        + ("相似问法：" + " | ".join(alternatives) + "\n" if alternatives else "")
        + "答案："
        + answer
    )
    if tokens(content) > parent_maximum:
        raise InvalidFile("FAQ group exceeds configured context budget")
    location = {
        "type": "manual",
        "title_path": [question, *alternatives],
        "warning": "人工补充，无原文件定位",
    }
    children = chunk([Block(answer, location)], **{**settings, "include_context": True})
    for child in children:
        source = json.loads(child["location"])
        child["location"] = json.dumps(
            {
                "type": "manual",
                "warning": location["warning"],
                "context_prefix": source["context_prefix"],
            },
            ensure_ascii=False,
        )
        child["source_text"] = ""
        child["context_ordinal"] = 0
    parent = context_row(
        {
            "source_text": "",
            "content": content,
            "location": json.dumps(
                {"type": "manual", "warning": location["warning"]}, ensure_ascii=False
            ),
            "token_count": tokens(content),
        },
        0,
        "FAQ",
        question,
        alternatives,
        answer,
    )
    return {"chunks": children, "contexts": [parent]}
