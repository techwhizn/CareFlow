/** Only the newest read may reach the UI, including when a request ignores abort. */
export function createLoader<T>(
  fetchValue: (path: string, signal: AbortSignal) => Promise<T>,
  update: (value: { path: string; data: T; loading: boolean; error: string }) => void,
  empty: T,
) {
  let generation = 0;
  let controller: AbortController | undefined;
  return {
    async load(path: string) {
      const current = ++generation;
      controller?.abort();
      const operation = new AbortController();
      controller = operation;
      update({ path, data: empty, loading: true, error: "" });
      try {
        const data = await fetchValue(path, operation.signal);
        if (current === generation && !operation.signal.aborted)
          update({ path, data, loading: false, error: "" });
      } catch (cause) {
        if (current === generation && !operation.signal.aborted)
          update({ path, data: empty, loading: false, error: cause instanceof Error ? cause.message : "读取失败" });
      }
    },
    cancel() { ++generation; controller?.abort(); },
  };
}
