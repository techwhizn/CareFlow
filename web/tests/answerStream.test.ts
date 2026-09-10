import test from "node:test";
import assert from "node:assert/strict";
import { consumeAnswerStream } from "../src/answerStream.ts";

test("UTF-8 and CRLF split across individual bytes preserve text and stop at done", async () => {
  const bytes = new TextEncoder().encode(
    ': keepalive\r\n\r\nevent: delta\r\ndata: {"text":"知识😀"}\r\n\r\nevent: usage\r\ndata: {"total_tokens":3}\r\n\r\nevent: done\r\ndata: {}\r\n\r\nevent: delta\r\ndata: {"text":"late"}\r\n\r\n',
  );
  let closed = false;
  let index = 0;
  const stream = new ReadableStream<Uint8Array>({
    pull(controller) {
      controller.enqueue(bytes.slice(index, ++index));
    },
    cancel() {
      closed = true;
    },
  });
  const events: unknown[] = [];
  await consumeAnswerStream(stream, (name, data) => events.push([name, data]));
  assert.deepEqual(events, [
    ["delta", { text: "知识😀" }],
    ["usage", { total_tokens: 3 }],
    ["done", {}],
  ]);
  assert.equal(closed, true);
});

test("truncated, malformed and oversized streams fail instead of completing", async () => {
  for (const text of [
    'event: delta\ndata: {"text":"partial"}\n\n',
    "event: delta\ndata: invalid\n\n",
    "x".repeat(65537),
  ]) {
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new TextEncoder().encode(text));
        controller.close();
      },
    });
    await assert.rejects(consumeAnswerStream(stream, () => {}));
  }
});

test("consumer cancellation closes source and error is terminal", async () => {
  let cancelled = false;
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(
        new TextEncoder().encode('event: delta\ndata: {"text":"first"}\n\n'),
      );
    },
    cancel() {
      cancelled = true;
    },
  });
  await assert.rejects(
    consumeAnswerStream(stream, () => {
      throw new Error("stop");
    }),
    /stop/,
  );
  assert.equal(cancelled, true);
  const failed = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(
        new TextEncoder().encode('event: error\ndata: {"code":"FAILED"}\n\n'),
      );
    },
  });
  await consumeAnswerStream(failed, (name) => assert.equal(name, "error"));
});

test("abort interrupts a pending body read and never delivers a late done", async () => {
  const control = new AbortController();
  let closed = false;
  const stream = new ReadableStream<Uint8Array>({
    cancel() {
      closed = true;
    },
  });
  const pending = consumeAnswerStream(
    stream,
    () => assert.fail("No late event"),
    control.signal,
  );
  control.abort();
  await assert.rejects(pending, { name: "AbortError" });
  assert.equal(closed, true);
});
