import json

import pytest

from careflow.context_chunking import manual_faq, process_blocks
from careflow.parsing import parse
from careflow.parsing_types import InvalidFile
from careflow.protocol_v1 import ParseCompletion


def test_parent_contexts_bound_children_in_the_same_source_document():
    text = "# Manual\n" + "A synthetic sentence about connections. " * 100
    result = process_blocks(
        parse(text.encode(), "fixture.md"),
        {
            "layout": "parent_child",
            "target": 40,
            "maximum": 60,
            "overlap": 0,
            "parent_maximum": 180,
        },
    )
    ParseCompletion.model_validate(result)
    assert len(result["contexts"]) > 1
    assert len(result["chunks"]) > len(result["contexts"])
    assert "".join(row["source_text"] for row in result["chunks"]) == text
    for child in result["chunks"]:
        parent = result["contexts"][child["context_ordinal"]]
        p = json.loads(parent["location"])
        c = json.loads(child["location"])
        assert p["block_start"] <= c["block_start"] < c["block_end"] <= p["block_end"]
        assert child["source_text"] == text[c["block_start"] : c["block_end"]]


def test_faq_keeps_question_aliases_answer_and_real_answer_offsets():
    text = "# Guide\n问题：如何重置？\n相似问法：怎么复位|重启步骤\n答案：先断电，再按复位键。\nQ: How to connect?\nA: Check the cable.\n"
    result = process_blocks(
        parse(text.encode(), "faq.md"),
        {"layout": "faq", "target": 80, "maximum": 100, "overlap": 0},
    )
    ParseCompletion.model_validate(result)
    assert [row["question"] for row in result["contexts"]] == [
        "如何重置？",
        "How to connect?",
    ]
    assert result["contexts"][0]["alternatives"] == ["怎么复位", "重启步骤"]
    assert "怎么复位" in result["chunks"][0]["content"]
    for row in result["chunks"]:
        location = json.loads(row["location"])
        assert (
            row["source_text"] == text[location["block_start"] : location["block_end"]]
        )
        assert result["contexts"][row["context_ordinal"]]["kind"] == "FAQ"


@pytest.mark.parametrize(
    "text",
    [
        "Q: question without answer",
        "Unlabelled content",
        "Q: question\nA: ",
        "Q: reset?\nDo not disconnect in emergencies.\nA: Disconnect.",
    ],
)
def test_invalid_faq_does_not_silently_discard_source(text):
    with pytest.raises(InvalidFile):
        process_blocks(parse(text.encode(), "faq.md"), {"layout": "faq"})


def test_manual_faq_does_not_parse_user_answer_as_more_records_or_invent_offsets():
    answer = "A normal answer.\nQ: a quoted question is part of this answer."
    result = manual_faq(
        "Question",
        ["Another phrasing"],
        answer,
        {"target": 30, "maximum": 50, "overlap": 0},
    )
    ParseCompletion.model_validate(result)
    assert len(result["contexts"]) == 1
    assert result["contexts"][0]["answer"] == answer
    for row in result["chunks"]:
        location = json.loads(row["location"])
        assert row["source_text"] == ""
        assert location["type"] == "manual"
        assert "block_start" not in location and "page" not in location
