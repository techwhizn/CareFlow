import { ArrowClockwise, Stack } from "@phosphor-icons/react";
import { useEffect, useState } from "react";
import type { Row } from "../api";
import { post } from "../api";
import { Badge, Empty, ErrorNote, Loading, useData } from "../ui";
import CleanupRequests from "../features/tasks/CleanupRequests";
export default function TasksPage() {
  const jobs = useData<Row[]>("/jobs", []),
    [error, setError] = useState("");
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
                <th>最后检查点</th>
                <th>最近心跳</th>
                <th>状态</th>
                <th>尝试次数</th>
                <th>索引与用量</th>
                <th>等待原因</th>
                <th>错误类型</th>
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
                    {(
                      {
                        QUEUED: "排队",
                        STARTED: "已领取",
                        SOURCE_READY: "原文件已读取",
                        PARSED: "解析完成",
                        INDEXING: "索引处理中",
                        INDEX_VERIFIED: "索引已验证",
                        DONE: "已完成",
                      } as Record<string, string>
                    )[j.checkpoint] || j.checkpoint}
                  </td>
                  <td>
                    {j.heartbeat_at
                      ? new Date(j.heartbeat_at).toLocaleString()
                      : "—"}
                  </td>
                  <td>
                    <Badge value={j.state} />
                  </td>
                  <td>{j.attempts} / 3</td>
                  <td>
                    {j.indexed_chunks != null && (
                      <div>
                        共 {j.indexed_chunks} 片 · 新算 {j.embedded_texts} ·
                        复用 {j.reused_chunks}
                      </div>
                    )}
                    {j.index_usage &&
                    (j.index_usage.model_calls > 0 ||
                      j.indexed_chunks != null) ? (
                      <>
                        <div>
                          已知 Embedding：{j.index_usage.known_embedding_tokens}{" "}
                          Token
                        </div>
                        <small>
                          {j.index_usage.model_calls} 次调用（含重试）
                          {j.index_usage.unknown_usage_calls > 0
                            ? ` · ${j.index_usage.unknown_usage_calls} 次用量未知或待回报`
                            : ""}
                        </small>
                      </>
                    ) : (
                      "—"
                    )}
                  </td>
                  <td>
                    {j.state === "QUEUED"
                      ? (
                          {
                            TENANT_NOTIFICATION_PENDING:
                              "等待本企业前序任务领取",
                            ENTITLEMENT_INACTIVE: "套餐未生效或已停用",
                            TASK_QUOTA_OR_CONCURRENCY: "等待处理额度或并发名额",
                            WAITING_FOR_WORKER: "已通知，等待处理器领取",
                          } as Record<string, string>
                        )[j.wait_reason] || "等待公平调度"
                      : "—"}
                  </td>
                  <td>{j.error_code || "—"}</td>
                  <td>
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
      <CleanupRequests />
    </>
  );
}
