import { useState } from "react";
import type { Row } from "../../api";
import { post } from "../../api";
import { ErrorNote, Loading, useData } from "../../ui";

type Report = {
  consistent: boolean;
  expected_count: number;
  actual_count: number;
  missing_count: number;
  extra_count: number;
  mismatched_count: number;
};
type History = {
  generations: Row[];
  checks: {
    id: string;
    created_at: string;
    report_json: string;
    generation_id: string | null;
    content_revision: number;
  }[];
};
export default function IndexMaintenance({
  version,
  busy,
  action,
}: {
  version: Row;
  busy: boolean;
  action: (run: () => Promise<unknown>) => Promise<void>;
}) {
  const path = `/document-versions/${version.id}/index`;
  const history = useData<History>(`${path}/history`, {
    generations: [],
    checks: [],
  });
  const [reason, setReason] = useState("");
  const latest = history.data.checks[0];
  const report: Report | undefined = latest
    ? JSON.parse(latest.report_json)
    : undefined;
  const ready = version.state === "READY" && !!version.configuration_id;
  return (
    <section>
      <h2>索引核对与重建</h2>
      <p>
        当前代际：
        {version.active_index_generation || "历史索引（尚未迁移代际）"}
      </p>
      <p>
        重建使用本版本的冻结模型配置。已发布索引在新代际逐项核对通过前持续可用；失败会保留旧代际。
      </p>
      <ErrorNote error={history.error} />
      <div className="button-row">
        <button
          disabled={busy || !ready}
          onClick={() =>
            void action(async () => {
              await post(`${path}/checks`);
              await history.reload();
            })
          }
        >
          逐项核对索引
        </button>
        <button
          disabled={busy}
          onClick={() =>
            void action(async () => {
              await history.reload();
            })
          }
        >
          刷新索引记录
        </button>
      </div>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          void action(async () => {
            await post(`${path}/rebuild`, {
              revision: version.revision,
              expected_generation_id: version.active_index_generation ?? null,
              reason,
            });
            setReason("");
            await history.reload();
          });
        }}
      >
        <label>
          重建原因
          <input
            value={reason}
            maxLength={1000}
            required
            onChange={(event) => setReason(event.target.value)}
            placeholder="例如：修复缺失索引或进行一致性恢复"
          />
        </label>
        <button disabled={busy || !ready || !reason.trim()}>建立新代际</button>
      </form>
      {history.loading ? (
        <Loading />
      ) : (
        report && (
          <p role="status">
            最近核对 {new Date(latest.created_at).toLocaleString()}（
            {latest.generation_id ===
              (version.active_index_generation ?? null) &&
            latest.content_revision === version.revision
              ? "当前代际与内容修订"
              : "历史代际或内容修订，请重新核对当前版本"}
            ）：
            {report.consistent ? "一致" : "发现差异"}；业务{" "}
            {report.expected_count} 片，索引 {report.actual_count} 片，缺失{" "}
            {report.missing_count}，多余 {report.extra_count}，内容或向量异常{" "}
            {report.mismatched_count}。
          </p>
        )
      )}
      {history.data.generations.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>代际</th>
                <th>状态</th>
                <th>切片数</th>
                <th>建立时间</th>
              </tr>
            </thead>
            <tbody>
              {history.data.generations.map((row) => (
                <tr key={row.id}>
                  <td>
                    <code>{row.id.slice(0, 12)}</code>
                  </td>
                  <td>
                    {(
                      {
                        BUILDING: "构建中",
                        ACTIVE: "使用中",
                        RETIRED: "已替换",
                        FAILED: "失败",
                        CANCELLED: "已取消",
                      } as Record<string, string>
                    )[row.state] || row.state}
                  </td>
                  <td>{row.chunk_count ?? "待核对"}</td>
                  <td>{new Date(row.created_at).toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p>记录保留最近100个代际和50次核对；完整清理状态在运维流程中跟踪。</p>
    </section>
  );
}
