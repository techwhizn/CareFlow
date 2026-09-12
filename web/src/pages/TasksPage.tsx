import { ExecutionCost } from "../features/billing/CostView";
import { ArrowClockwise, Stack } from "@phosphor-icons/react";
import { useEffect, useState } from "react";
import type { Row } from "../api";
import { post } from "../api";
import { Badge, Empty, ErrorNote, Loading, useData } from "../ui";
import CleanupRequests from "../features/tasks/CleanupRequests";
export default function TasksPage() {
  const jobs = useData<Row[]>("/jobs", []),
    [error, setError] = useState(""),
    [costId, setCostId] = useState("");
  useEffect(() => {
    const timer = setInterval(() => void jobs.reload(), 10000);
    return () => clearInterval(timer);
  }, []);
  return (
    <>
      <div className="page-heading">
        <div>
          <h1>任务中心</h1>
          <p>按真实处理阶段跟踪进度，失败信息保留用于排查。</p>
        </div>
        <button onClick={() => void jobs.reload()}>
          <ArrowClockwise />
          刷新
        </button>
      </div>
      <ErrorNote error={error || jobs.error} />
      {jobs.loading && !jobs.data.length ? (
        <Loading />
      ) : !jobs.data.length ? (
        <Empty
          icon={Stack}
          title="暂无处理任务"
          detail="上传文件或重建索引后，任务会显示在这里。"
        />
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>任务</th>
                <th>阶段</th>
                <th>状态</th>
                <th>进度信息</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {jobs.data.map((j) => (
                <tr key={j.id}>
                  <td>
                    <code>{j.id.slice(0, 12)}</code>
                  </td>
                  <td>
                    {j.kind === "PARSE" ? "文档解析与切片" : "向量化与索引"}
                  </td>
                  <td>
                    <Badge value={j.state} />
                  </td>
                  <td>
                    <div>{j.checkpoint || "等待开始"}</div>
                    <small className="task-meta">{j.state === "QUEUED" ? (j.wait_reason || "等待处理器领取") : j.error_code || (j.heartbeat_at ? `最近更新 ${new Date(j.heartbeat_at).toLocaleString()}` : `第 ${j.attempts || 0} 次尝试`)}</small>
                    <details className="task-details">
                      <summary>查看详细信息</summary>
                      <div className="task-details-body">
                        <span>检查点：{j.checkpoint || "—"}</span>
                        <span>尝试次数：{j.attempts || 0} / 3</span>
                        {j.indexed_chunks != null && <span>索引切片：{j.indexed_chunks}（新算 {j.embedded_texts}，复用 {j.reused_chunks}）</span>}
                        {j.ocr_usage && <span>OCR：完成 {j.ocr_usage.completed_pages} 页，失败 {j.ocr_usage.failed_pages} 页</span>}
                        {j.index_usage && <span>Embedding：{j.index_usage.known_embedding_tokens} Token，调用 {j.index_usage.model_calls} 次</span>}
                      </div>
                    </details>
                  </td>
                  <td>
                    <button onClick={() => setCostId(j.id)}>成本核算</button>
                    {["QUEUED", "RUNNING"].includes(j.state) && (
                      <button
                        onClick={async () => {
                          try {
                            await post(`/jobs/${j.id}/cancel`);
                            await jobs.reload();
                          } catch (e) {
                            setError((e as Error).message);
                          }
                        }}
                      >
                        取消
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {costId && <ExecutionCost key={costId} path={`/jobs/${costId}/cost`} />}
      <CleanupRequests />
    </>
  );
}
