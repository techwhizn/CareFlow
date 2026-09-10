import assert from "node:assert/strict";
import test from "node:test";
import { citationSegments } from "../src/features/retrieval/citationSegments.ts";

test("only current authorized evidence becomes an interactive reference", () => {
  const segments = citationSegments("事实 [current] [old] <script> [1]", [
    "current",
  ]);
  assert.deepEqual(
    segments.filter((s) => s.citation),
    [{ text: "[current]", citation: "current" }],
  );
  assert.equal(
    segments.map((s) => s.text).join(""),
    "事实 [current] [old] <script> [1]",
  );
});
test("incomplete streamed reference stays plain text", () => {
  assert.deepEqual(citationSegments("事实 [current", ["current"]), [
    { text: "事实 [current" },
  ]);
});
