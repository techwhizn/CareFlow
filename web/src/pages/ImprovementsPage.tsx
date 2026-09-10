import { useState } from "react";
import { put, request } from "../api";
import type { Row } from "../api";
import { ErrorNote, useData } from "../ui";

const states: Record<string, string> = {
  OPEN: "待处理",
  IN_PROGRESS: "处理中",
  RESOLVED: "已解决",
  DISMISSED: "不采纳",
};
const reasons: Record<string, string> = {
  WRONG_ANSWER: "答案不正确",
  WRONG_SOURCE: "引用不支持答案",
  MISSING_KNOWLEDGE: "资料不完整",
  OUTDATED: "资料已过时",
  OTHER: "其他",
};
const retrievalStates: Record<string, string> = {
  NO_MATCH: "未召回证据",
  BELOW_THRESHOLD: "证据低于相关性门槛",
  AVAILABLE: "证据可用",
  DEGRADED: "降级检索",
  SCORE_UNAVAILABLE: "评分服务不可用",
};
export default function ImprovementsPage() {
  const tasks = useData<Row[]>("/improvements", []),
    bases = useData<Row[]>("/knowledge-bases", []);
  const [selected, setSelected] = useState<Row | null>(null),
    [assignees, setAssignees] = useState<Row[]>([]),
    [state, setState] = useState("OPEN"),
    [assignee, setAssignee] = useState(""),
    [resolution, setResolution] = useState(""),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  return (
    <>
      <div className="page-heading">
        <div>
          <h1>知识改进</h1>
          <p>跟进差评与无结果查询，保留问题、证据和配置来源。</p>
        </div>
        <button onClick={() => void tasks.reload()}>刷新任务</button>
      </div>
      <ErrorNote error={error || tasks.error || bases.error} />
      <div className="answer-layout">
        <div>
          {!tasks.data.length && <p>暂无可访问的改进任务。</p>}
          {tasks.data.map((task) => (
            <button
              key={task.id}
              disabled={busy}
              onClick={async () => {
                setBusy(true);
                setSelected(null);
                setError("");
                try {
                  const detail = await request<Row>(`/improvements/${task.id}`);
                  setSelected(detail);
                  setState(detail.state);
                  setAssignee(detail.assignee_id || "");
                  setResolution(detail.resolution || "");
                  setAssignees(
                    detail.can_manage
                      ? await request<Row[]>(
                          `/improvements/${task.id}/assignees`,
                        )
                      : [],
                  );
                } catch (e) {
                  setSelected(null);
                  setError((e as Error).message);
                } finally {
                  setBusy(false);
                }
              }}
            >
              {states[task.state]} ·{" "}
              {bases.data.find((b) => b.id === task.kb_id)?.name || "知识库"} ·{" "}
              {task.source_kind === "ANSWER" ? "回答差评" : "无结果查询"}
              <small>{task.description || "暂无补充说明"}</small>
            </button>
          ))}
        </div>
        {selected && (
          <section className="query-panel">
            <h2>改进任务详情</h2>
            <p>
              {selected.query.question || "调试正文未采集或已按保留策略清除"}
            </p>
            <p>{selected.description}</p>
            {selected.answer && (
              <>
                <h3>关联答案与当前反馈</h3>
                <p>{selected.answer.content}</p>
                <p>
                  原因：{reasons[selected.reason] || selected.reason} ·{" "}
                  {selected.answer.feedback_comment}
                </p>
              </>
            )}
            <p>
              查询状态：
              {retrievalStates[selected.query.evidence_status] ||
                selected.query.evidence_status}
            </p>
            <small>
              知识配置：{selected.query.configuration_id || "无已发布配置"} ·
              应用修订：
              {selected.query.application_revision < 0
                ? "未绑定应用"
                : selected.query.application_revision}
            </small>
            {(selected.evidence || []).map((source: Row) => (
              <p key={source.chunk_id}>
                <small>
                  文档 {source.document_id} · 版本 {source.version_id} · 切片{" "}
                  {source.chunk_id}
                </small>
              </p>
            ))}
            {selected.can_manage ? (
              <>
                <label>
                  处理状态
                  <select
                    value={state}
                    onChange={(e) => setState(e.target.value)}
                  >
                    {Object.entries(states).map(([id, label]) => (
                      <option key={id} value={id}>
                        {label}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  负责人
                  <select
                    value={assignee}
                    onChange={(e) => setAssignee(e.target.value)}
                  >
                    <option value="">暂未分配</option>
                    {assignees.map((a) => (
                      <option key={a.id} value={a.id}>
                        {a.name}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  处理说明
                  <textarea
                    value={resolution}
                    onChange={(e) => setResolution(e.target.value)}
                    maxLength={2000}
                  />
                </label>
                <button
                  disabled={busy}
                  onClick={async () => {
                    setBusy(true);
                    setError("");
                    try {
                      setSelected(
                        await put(`/improvements/${selected.id}`, {
                          revision: selected.revision,
                          state,
                          assignee_id: assignee || null,
                          resolution,
                        }),
                      );
                      void tasks.reload();
                    } catch (e) {
                      setError((e as Error).message);
                    } finally {
                      setBusy(false);
                    }
                  }}
                >
                  保存处理结果
                </button>
              </>
            ) : (
              <p>
                处理状态：{states[selected.state]} ·{" "}
                {selected.resolution || "等待知识管理员处理"}
              </p>
            )}
          </section>
        )}
      </div>
    </>
  );
}
