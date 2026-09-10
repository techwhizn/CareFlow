import assert from "node:assert/strict";
import test from "node:test";
import { importCases } from "../src/features/evaluation/importCases.ts";
const row = {
  id: "case-1",
  question: "Synthetic question",
  reference_answer: "Synthetic answer",
  category: "DIRECT",
  answerability: "ANSWERABLE",
  expected_chunk_ids: [],
  test_subject_kind: "MEMBER",
  test_subject_id: "00000000-0000-0000-0000-000000000001",
};
test("dataset imports validate shape and discard unrelated fields", () => {
  assert.deepEqual(importCases({ cases: [{ ...row, unrelated: "discard" }] }), [
    row,
  ]);
});
test("malformed and duplicate cases fail before rendering", () => {
  for (const input of [
    null,
    [null],
    [row, row],
    [{ ...row, expected_chunk_ids: "not-an-array" }],
    [{ ...row, test_subject_id: "bad" }],
  ])
    assert.throws(() => importCases(input));
});
