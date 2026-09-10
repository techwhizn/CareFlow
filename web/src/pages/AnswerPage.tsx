import MetadataFilterEditor, { serializeFilters } from "../features/retrieval/MetadataFilterEditor";
import type { FilterDraft } from "../features/retrieval/MetadataFilterEditor";
import RelevanceThreshold from "../components/RelevanceThreshold";
import {
  ChatCircleText,
  FileText,
  PaperPlaneTilt,
  Stop,
} from "@phosphor-icons/react";
import { useEffect, useState } from "react";
import type { Row } from "../api";
import { post, streamAnswer } from "../api";
import { Empty, ErrorNote, useData } from "../ui";
export default function AnswerPage() {
  const [query, setQuery] = useState(""),
    [answer, setAnswer] = useState(""),
    [minimumScore, setMinimumScore] = useState(""),
    [filters, setFilters] = useState<FilterDraft[]>([]),
    [busy, setBusy] = useState(false),
    [stage, setStage] = useState(""),
    [error, setError] = useState(""),
    [evidence, setEvidence] = useState<Row[]>([]),
    [controller, setController] = useState<AbortController | null>(null),
    [answerId, setAnswerId] = useState("");
  const history = useData<Row[]>("/answers", []);
  useEffect(() => () => controller?.abort(), [controller]);
  return (
    <>
      <div className="page-heading">
        <div>
          <h1>引用问答</h1>
          <p>基于当前授权且已发布的知识回答，关键内容可核对来源。</p>
        </div>
      </div>
      <div className="answer-layout">
        <div>
          <form
            className="query-panel"
            onSubmit={async (e) => {
              e.preventDefault();
              const control = new AbortController();
              setController(control);
              setBusy(true);
              setError("");
              setAnswer("");
              setAnswerId("");
              setEvidence([]);
              try {
                await streamAnswer(
                  {
                    query,
                    knowledge_base_ids: [],
                    mode: "hybrid",
                    limit: 6,
                    debug: false,
                    minimum_rerank_score: minimumScore === "" ? null : Number(minimumScore),
                filters: serializeFilters(filters),
                  },
                  (name, data) => {
                    if (name === "status")
                      setStage(
                        data.stage === "retrieval"
                          ? "检索与重排中"
                          : "生成回答中",
                      );
                    if (name === "delta") setAnswer((a) => a + data.text);
                    if (name === "citations") setEvidence(data.evidence);
                    if (name === "done") {
                      setStage("回答完成");
                      setAnswerId(data.answer_id);
                      void history.reload();
                    }
                    if (name === "error") {
                      setStage("回答未完成");
                      setError(data.message);
                    }
                  },
                  control.signal,
                );
              } catch (e) {
                setError(
                  (e as Error).name === "AbortError"
                    ? "已停止，当前回答未完成"
                    : (e as Error).message,
                );
                setStage("回答未完成");
              } finally {
                setBusy(false);
              }
            }}
          >
<RelevanceThreshold value={minimumScore} onChange={setMinimumScore} disabled={busy} />
<MetadataFilterEditor value={filters} onChange={setFilters} disabled={busy} />
            <label>
              向知识库提问
              <textarea
                rows={3}
                placeholder="你想了解什么？"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                required
                maxLength={4000}
              />
            </label>
            <div className="section-title">
              <span className="muted">仅使用当前可访问的知识</span>
              {busy ? (
                <button type="button" onClick={() => controller?.abort()}>
                  <Stop />
                  停止
                </button>
              ) : (
                <button className="primary">
                  <PaperPlaneTilt />
                  发送问题
                </button>
              )}
            </div>
          </form>
          <ErrorNote error={error} />
          {answer ? (
            <div className="answer-content">
              <span className="eyebrow">{stage}</span>
              <p>{answer}</p>
              {!!evidence.length && (
                <div className="citations">
                  <h3>引用证据</h3>
                  {evidence.map((c) => (
                    <details key={c.id}>
                      <summary>
                        <FileText />
                        {c.title}
                        <code>{c.id.slice(0, 8)}</code>
                      </summary>
                      <p>{c.content}</p>
                      <small>{c.location}</small>
                    </details>
                  ))}
                </div>
              )}
              {answerId && (
                <div className="button-row">
                  {[
                    ["helpful", "有帮助"],
                    ["incorrect", "答案有误"],
                  ].map(([feedback, label]) => (
                    <button
                      key={feedback}
                      onClick={async () => {
                        try {
                          await post(`/answers/${answerId}/feedback`, {
                            feedback,
                          });
                          setStage("反馈已记录");
                        } catch (e) {
                          setError((e as Error).message);
                        }
                      }}
                    >
                      {label}
                    </button>
                  ))}
                </div>
              )}
            </div>
          ) : (
            !busy && (
              <Empty
                icon={ChatCircleText}
                title="每个答案，都有出处"
                detail="从资料出发，遇到证据不足时明确说明。"
              />
            )
          )}
        </div>
        <aside className="history">
          <h3>历史回答</h3>
          <p>资料删除或撤权后，相关答案会被隐藏。</p>
          <ErrorNote error={history.error} />
          {history.data.map((a) => (
            <button
              key={a.id}
              onClick={() => {
                setQuery(a.question);
                setAnswer(a.content);
                setAnswerId(a.id);
                setEvidence([]);
                setStage("历史回答");
              }}
            >
              {a.question}
              <small>{new Date(a.created_at).toLocaleString()}</small>
            </button>
          ))}
          {!history.data.length && <p>暂无历史回答</p>}
        </aside>
      </div>
    </>
  );
}
