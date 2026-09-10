import { useState } from "react";
import { put } from "../../api";
import { ErrorNote, useData } from "../../ui";

type Limits = {
  requests_per_minute: number;
  concurrent_requests: number;
  revision: number;
};

export default function ApplicationLimits({
  applicationId,
}: {
  applicationId: string;
}) {
  const path = `/applications/${applicationId}/limits`;
  const limits = useData<Limits | null>(path, null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [saved, setSaved] = useState(false);
  return (
    <details>
      <summary>调用限流</summary>
      <p>
        同一应用的所有凭证共享限制。设置保存后立即生效，已开始的调用继续执行。
      </p>
      <ErrorNote error={error || limits.error} />
      {limits.data && (
        <form
          key={limits.data.revision}
          onSubmit={async (e) => {
            e.preventDefault();
            const data = new FormData(e.currentTarget);
            setBusy(true);
            setError("");
            setSaved(false);
            try {
              await put(path, {
                requests_per_minute: Number(data.get("minute")),
                concurrent_requests: Number(data.get("concurrent")),
                revision: limits.data!.revision,
              });
              await limits.reload();
              setSaved(true);
            } catch (e) {
              setError((e as Error).message);
            } finally {
              setBusy(false);
            }
          }}
        >
          <label>
            每分钟最多调用次数
            <input
              name="minute"
              type="number"
              min={1}
              max={6000}
              required
              defaultValue={limits.data.requests_per_minute}
            />
          </label>
          <label>
            最多同时调用数
            <input
              name="concurrent"
              type="number"
              min={1}
              max={100}
              required
              defaultValue={limits.data.concurrent_requests}
            />
          </label>
          <button disabled={busy}>保存限流</button>
        </form>
      )}
      {saved && <p role="status">限流设置已保存</p>}
      <button
        disabled={busy}
        onClick={() => {
          setError("");
          setSaved(false);
          void limits.reload();
        }}
      >
        刷新限流设置
      </button>
    </details>
  );
}
