export async function consumeAnswerStream(
  stream: ReadableStream<Uint8Array>,
  onEvent: (name: string, data: Record<string, unknown>) => void,
  signal?: AbortSignal,
) {
  const reader = stream.getReader();
  const decoder = new TextDecoder("utf-8", { fatal: true });
  let buffer = "";
  const abort = () => {
    void reader.cancel().catch(() => {});
  };
  const checkCancelled = () => {
    if (signal?.aborted) throw new DOMException("Stopped", "AbortError");
  };
  signal?.addEventListener("abort", abort, { once: true });
  try {
    while (true) {
      checkCancelled();
      const { value, done } = await reader.read();
      checkCancelled();
      buffer += done
        ? decoder.decode()
        : decoder.decode(value, { stream: true });
      let boundary: RegExpExecArray | null;
      while ((boundary = /\r?\n\r?\n/.exec(buffer))) {
        const raw = buffer.slice(0, boundary.index);
        buffer = buffer.slice(boundary.index + boundary[0].length);
        if (raw.length > 65536) throw new Error("流式事件超过大小限制");
        let name = "message";
        const data: string[] = [];
        for (const line of raw.split(/\r?\n/)) {
          if (line.startsWith("event:")) name = line.slice(6).trim();
          if (line.startsWith("data:"))
            data.push(line.slice(5).replace(/^ /, ""));
        }
        if (!data.length) continue;
        const payload: unknown = JSON.parse(data.join("\n"));
        if (!payload || typeof payload !== "object" || Array.isArray(payload))
          throw new Error("流式事件格式不正确");
        onEvent(name, payload as Record<string, unknown>);
        if (name === "done" || name === "error") return;
      }
      if (buffer.length > 65536) throw new Error("流式事件超过大小限制");
      if (done) throw new Error("连接提前结束，回答未完成");
    }
  } finally {
    signal?.removeEventListener("abort", abort);
    try {
      await reader.cancel();
    } catch {
      /* An aborted fetch may already have closed its reader. */
    }
    reader.releaseLock();
  }
}
