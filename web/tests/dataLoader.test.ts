import { test } from "node:test";
import assert from "node:assert/strict";
import { createLoader } from "../src/dataLoader.ts";

test("late previous-version response never replaces the current preview", async () => {
  const waits = new Map<string, (value: string[]) => void>();
  const updates: {path: string; data: string[]; loading: boolean; error: string}[] = [];
  const loader = createLoader<string[]>((path) => new Promise(resolve => waits.set(path, resolve)), value => updates.push(value), []);
  const first = loader.load("old-version"), second = loader.load("new-version");
  waits.get("new-version")!(["new source"]); await second;
  waits.get("old-version")!(["old source"]); await first;
  assert.deepEqual(updates.at(-1)?.data, ["new source"]);
  assert.equal(updates.filter(row => row.data.includes("old source")).length, 0);
});

test("failed authorization clears previously loaded content", async () => {
  let allowed = true;
  const updates: {data: string[]; error: string}[] = [];
  const loader = createLoader<string[]>(async () => {
    if (!allowed) throw new Error("Resource unavailable");
    return ["restricted source"];
  }, value => updates.push(value), []);
  await loader.load("version"); allowed = false; await loader.load("version");
  assert.deepEqual(updates.at(-1)?.data, []);
  assert.equal(updates.at(-1)?.error, "Resource unavailable");
});

test("unmounted preview ignores completion even without network abort support", async () => {
  let resolve: (value: string[]) => void = () => {};
  const updates: unknown[] = [];
  const loader = createLoader<string[]>(() => new Promise(done => {resolve=done;}), row => updates.push(row), []);
  const request = loader.load("version"); loader.cancel(); resolve(["late data"]); await request;
  assert.equal(updates.length, 1);
});
