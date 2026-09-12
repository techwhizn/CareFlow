import FeedbackPanel from "../features/retrieval/FeedbackPanel";
import { citationSegments } from "../features/retrieval/citationSegments";
import AnswerCitations from "../features/retrieval/AnswerCitations";
import MetadataFilterEditor, {
  serializeFilters,
} from "../features/retrieval/MetadataFilterEditor";
import type { FilterDraft } from "../features/retrieval/MetadataFilterEditor";
import RelevanceThreshold from "../components/RelevanceThreshold";
import { ChatCircleText, PaperPlaneTilt, Stop } from "@phosphor-icons/react";
import { useEffect, useRef, useState } from "react";
import type { Row } from "../api";
import { request, streamAnswer } from "../api";
import { Empty, ErrorNote, useData } from "../ui";
export default function AnswerPage() {
  const [query, setQuery] = useState(""),
    [kb, setKb] = useState(""),
    [appId, setAppId] = useState(""),
    [answer, setAnswer] = useState(""),
    [minimumScore, setMinimumScore] = useState(""),
    [filters, setFilters] = useState<FilterDraft[]>([]),
    [busy, setBusy] = useState(false),
    [stage, setStage] = useState(""),
    [error, setError] = useState(""),
    [evidence, setEvidence] = useState<Row[]>([]),
    [answerId, setAnswerId] = useState(""),
    [conversationId, setConversationId] = useState(""),
    [contextInfo, setContextInfo] = useState("新会话"),
    [usage, setUsage] = useState<Row | null>(null);
  const history = useData<Row[]>("/answers", []);
  const knowledge = useData<Row[]>("/knowledge-bases", []);
  const applications = useData<Row[]>("/applications/available", []);
  const controller = useRef<AbortController | null>(null);
  useEffect(() => () => controller.current?.abort(), []);
  function resetConversation() {
    controller.current?.abort();
    controller.current = null;
    setBusy(false);
    setConversationId("");
    setContextInfo("新会话");
    setAnswer("");
    setAnswerId("");
    setEvidence([]);
    setUsage(null);
    setError("");
    setStage("");
  }
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
              controller.current?.abort();
              controller.current = control;
              setBusy(true);
              setError("");
              setAnswer("");
              setAnswerId("");
              setEvidence([]);
              setUsage(null);
              try {
                await streamAnswer(
                  {
                    query,
                    conversation_id: conversationId || null,
                    application_id: appId || null,
                    knowledge_base_ids: kb ? [kb] : [],
                    mode: null,
                    limit: 6,
                    debug: false,
                    minimum_rerank_score:
                      minimumScore === "" ? null : Number(minimumScore),
                    filters: serializeFilters(filters),
                  },
                  (name, data) => {
                    if (
                      control.signal.aborted ||
                      controller.current !== control
                    )
                      return;
                    if (name === "start") {
                      setConversationId(data.conversation_id);
                      setContextInfo(
                        `已带入 ${data.context_rounds} 轮上下文 · ${data.context_tokens} / ${data.context_token_limit ?? 3000} Token`,
                      );
                    }
                    if (name === "status")
                      setStage(
                        data.stage === "retrieval"
                          ? "检索与重排中"
                          : "生成回答中",
                      );
                    if (name === "usage") setUsage(data);
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
                if (controller.current !== control) return;
                setError(
                  (e as Error).name === "AbortError"
                    ? "已停止，当前回答未完成"
                    : (e as Error).message,
                );
                setStage("回答未完成");
              } finally {
                if (controller.current === control) setBusy(false);
              }
            }}
          >
            <label>
              问答应用
              <select
                value={appId}
                disabled={busy}
                onChange={(event) => {
                  resetConversation();
                  setAppId(event.target.value);
                  setKb("");
                }}
              >
                <option value="">直接访问知识库</option>
                {applications.data.map((app) => (
                  <option key={app.id} value={app.id}>
                    {app.name}
                  </option>
                ))}
              </select>
            </label>
            <ErrorNote error={applications.error} />
            <label>
              知识范围
              <select
                value={kb}
                onChange={(event) => {
                  resetConversation();
                  setKb(event.target.value);
                }}
                disabled={busy}
              >
                <option value="">全部授权知识库</option>
                {knowledge.data.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.name}
                  </option>
                ))}
              </select>
              <small>查询配置不同的知识库，请分别选择后提问。</small>
            </label>
            <ErrorNote error={knowledge.error} />
            <details className="search-advanced">
              <summary>高级筛选（可选）</summary>
              <div className="search-advanced-body">
                <RelevanceThreshold value={minimumScore} onChange={setMinimumScore} disabled={busy} />
                <MetadataFilterEditor value={filters} onChange={setFilters} disabled={busy} />
              </div>
            </details>
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
              <span className="muted">{contextInfo}</span>
              <button type="button" disabled={busy} onClick={resetConversation}>
                新建会话
              </button>
              {busy ? (
                <button
                  key="stop"
                  type="button"
                  onClick={() => {
                    controller.current?.abort();
                    setStage("回答未完成");
                    setError("已停止，当前回答未完成");
                    setBusy(false);
                  }}
                >
                  <Stop />
                  停止
                </button>
              ) : (
                <button key="send" className="primary">
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
              <p>
                {citationSegments(
                  answer,
                  evidence.map((c) => c.id),
                ).map((segment, index) =>
                  segment.citation ? (
                    <button
                      key={index}
                      className="text-button"
                      onClick={() => {
                        const target = document.getElementById(
                          `citation-${segment.citation}`,
                        );
                        if (target instanceof HTMLDetailsElement) {
                          target.open = true;
                          target.scrollIntoView({
                            behavior: "smooth",
                            block: "center",
                          });
                        }
                      }}
                    >
                      {segment.text}
                    </button>
                  ) : (
                    <span key={index}>{segment.text}</span>
                  ),
                )}
              </p>
              {usage && (
                <small>
                  生成模型报告：输入 {usage.input_tokens ?? "未知"} Token · 输出{" "}
                  {usage.output_tokens ?? "未知"} Token · 总计{" "}
                  {usage.total_tokens ?? "未知"} Token
                </small>
              )}
              {!!evidence.length && (
                <AnswerCitations answerId={answerId} evidence={evidence} />
              )}
              {answerId && (
                <FeedbackPanel key={answerId} answerId={answerId} kb={kb} />
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
              disabled={busy}
              onClick={async () => {
                resetConversation();
                const control = new AbortController();
                controller.current = control;
                try {
                  const saved = await request<Row>(`/answers/${a.id}`, {
                    signal: control.signal,
                  });
                  if (controller.current !== control) return;
                  if (saved.conversation_id) {
                    const conversation = await request<Row>(
                      `/conversations/${saved.conversation_id}`,
                      { signal: control.signal },
                    );
                    if (controller.current !== control) return;
                    const bases = JSON.parse(
                      conversation.knowledge_base_ids,
                    ) as string[];
                    // Multi-base and application sessions remain available through their API scope.
                    if (bases.length <= 1) {
                      setConversationId(saved.conversation_id);
                      setAppId(conversation.application_id || "");
                      setKb(bases[0] || "");
                      setContextInfo(
                        `会话已有 ${conversation.revision} 轮回答`,
                      );
                    }
                  }
                  setQuery(saved.question);
                  setAnswer(saved.content);
                  setAnswerId(saved.id);
                  setEvidence(saved.evidence);
                  setStage("历史回答");
                } catch (error) {
                  if (controller.current !== control || control.signal.aborted)
                    return;
                  setError((error as Error).message);
                  void history.reload();
                }
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
