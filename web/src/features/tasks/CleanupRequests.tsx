import { useState } from "react";
import { post } from "../../api";
import { ErrorNote, Loading, useData } from "../../ui";

type Cleanup = {
  id: string;
  resource_type: string;
  resource_id: string;
  state: string;
  phase: string;
  failures: number;
  error_code: string | null;
  deadline_at: string | null;
  overdue: boolean | number;
};
const phases: Record<string, string> = {
  PREPARE: "准备清理",
  WAIT_CHILDREN: "等待下属数据清理",
  VECTOR_DELETE: "删除索引",
  VECTOR_COMPACTION: "等待索引压实",
  CACHE: "核对缓存归属",
  CACHE_DELETE: "删除独占缓存",
  CACHE_COMPACTION: "等待缓存压实",
  OBJECT: "删除原文件版本",
  CONTENT: "清理正文副本",
  DONE: "应用数据清理完成",
};
export default function CleanupRequests() {
  const requests = useData<Cleanup[]>("/cleanup-requests", []);
  const [selected, setSelected] = useState("");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  return (
    <section>
      <h2>数据清理记录</h2>
      <p>
        删除后立即停止访问；清理任务按阶段移除索引、独占缓存、原文件和正文副本。最近100条记录按当前身份权限展示。
      </p>
      <button disabled={busy} onClick={() => void requests.reload()}>
        刷新清理记录
      </button>
      <ErrorNote error={error || requests.error} />
      {requests.loading ? (
        <Loading />
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>资源</th>
                <th>状态</th>
                <th>阶段</th>
                <th>清理期限</th>
                <th>失败次数</th>
                <th>错误</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {requests.data.map((row) => (
                <tr key={row.id}>
                  <td>
                    {row.resource_type}
                    <br />
                    <code>{row.resource_id.slice(0, 12)}</code>
                  </td>
                  <td>
                    {(
                      {
                        PENDING: "等待执行",
                        RUNNING: "执行中",
                        RETRY: "等待重试",
                        BLOCKED: "范围校验未通过",
                        DONE: "已完成",
                      } as Record<string, string>
                    )[row.state] || row.state}
                    {!!row.overdue && " · 已逾期"}
                  </td>
                  <td>{phases[row.phase] || row.phase}</td>
                  <td>
                    {row.deadline_at
                      ? new Date(row.deadline_at).toLocaleString()
                      : "待安排"}
                  </td>
                  <td>{row.failures}</td>
                  <td>{row.error_code || "—"}</td>
                  <td>
                    {["RETRY", "BLOCKED"].includes(row.state) && (
                      <button
                        disabled={busy}
                        onClick={() => {
                          setSelected(row.id);
                          setReason("");
                        }}
                      >
                        安排重试
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {requests.data.length === 0 && <p>暂无可查看的清理记录。</p>}
        </div>
      )}
      {selected && (
        <form
          onSubmit={async (event) => {
            event.preventDefault();
            setBusy(true);
            setError("");
            try {
              await post(`/cleanup-requests/${selected}/retry`, { reason });
              setSelected("");
              await requests.reload();
            } catch (failure) {
              setError((failure as Error).message);
            } finally {
              setBusy(false);
            }
          }}
        >
          <label>
            重试原因
            <input
              required
              maxLength={1000}
              value={reason}
              onChange={(event) => setReason(event.target.value)}
            />
          </label>
          <button disabled={busy || !reason.trim()}>提交重试</button>
          <button type="button" disabled={busy} onClick={() => setSelected("")}>
            取消
          </button>
        </form>
      )}
      <p>
        完成状态不代表备份已到期或磁盘已安全擦除。备份保留30天，底层存储按运维配置回收压实后的旧文件。
      </p>
    </section>
  );
}
