import { useState } from "react";
import { request, type Row } from "../../api";
import { ErrorNote, useData } from "../../ui";

export default function RetentionPanel() {
  const policy = useData<Row | null>("/retention", null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  return (
    <details>
      <summary>调试与审计保留策略</summary>
      <ErrorNote error={error || policy.error} />
      <p>
        调试问题正文默认保留30天，审计元数据180天。关闭采集后，新调试记录不保存问题正文；既有调试正文由后台分批清除。问答历史、会话和改进任务属于产品数据，不在此处自动删除。
      </p>
      {policy.data && (
        <form
          key={policy.data.revision}
          onSubmit={async (e) => {
            e.preventDefault();
            const data = new FormData(e.currentTarget);
            setBusy(true);
            setError("");
            try {
              await request("/retention", {
                method: "PUT",
                body: JSON.stringify({
                  debug_body_collection: data.get("collect") === "on",
                  debug_retention_days: Number(data.get("debug")),
                  audit_retention_days: Number(data.get("audit")),
                  revision: policy.data!.revision,
                }),
              });
              await policy.reload();
            } catch (e) {
              setError((e as Error).message);
            } finally {
              setBusy(false);
            }
          }}
        >
          <label>
            <input
              type="checkbox"
              name="collect"
              defaultChecked={!!policy.data.debug_body_collection}
            />
            采集调试问题正文
          </label>
          <label>
            调试正文保留天数
            <input
              type="number"
              name="debug"
              min="1"
              max="3650"
              required
              defaultValue={policy.data.debug_retention_days}
            />
          </label>
          <label>
            审计元数据保留天数
            <input
              type="number"
              name="audit"
              min="1"
              max="3650"
              required
              defaultValue={policy.data.audit_retention_days}
            />
          </label>
          <button disabled={busy}>保存保留策略</button>
        </form>
      )}
    </details>
  );
}
