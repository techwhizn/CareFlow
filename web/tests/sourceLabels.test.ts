import { test } from "node:test";
import assert from "node:assert/strict";
import { sourceLabels } from "../src/features/knowledge/sourceLabels.ts";

test("text labels combine source-block and selected-slice offsets", () => {
  assert.deepEqual(sourceLabels({type:"text",start:120,block_start:15,block_end:34},false),["原文字符区间 [135, 154)"]);
});
test("table labels retain exact row and sheet coordinates", () => {
  assert.deepEqual(sourceLabels({type:"table",sheet:"规格",row:7,cell_range:"A7:D7",column_start:1,column_end:4},false),["工作表：规格","第 7 行","单元格 A7:D7","列 1–4"]);
});
test("manual provenance never displays source coordinates even if stale fields exist", () => {
  assert.deepEqual(sourceLabels({type:"pdf",page:4},true),["人工补充或修订，无原文件定位"]);
});
