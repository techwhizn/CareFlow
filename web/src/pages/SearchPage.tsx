import ImprovementRequest from "../features/retrieval/ImprovementRequest";
import { sourceLabels } from "../features/knowledge/sourceLabels";
import MetadataFilterEditor, {
  serializeFilters,
} from "../features/retrieval/MetadataFilterEditor";
import type { FilterDraft } from "../features/retrieval/MetadataFilterEditor";
import RelevanceThreshold from "../components/RelevanceThreshold";
import RetrievalTrace from "../components/RetrievalTrace";
import { ArrowRight, FileText, MagnifyingGlass } from "@phosphor-icons/react";
import { useState } from "react";
import type { Row } from "../api";
import { post } from "../api";
import { Empty, ErrorNote, Loading, useData } from "../ui";
function evidenceSource(row: Row): string {
  try {
    const location = JSON.parse(row.context_location || row.location || "{}");
    return (
      sourceLabels(location, row.origin === "MANUAL").join(" · ") ||
      "来源定位待核对"
    );
  } catch {
    return "来源定位待核对";
  }
}

export default function SearchPage() {
  const k = useData<Row[]>("/knowledge-bases", []),
    [query, setQuery] = useState(""),
    [kb, setKb] = useState(""),
    [appId, setAppId] = useState(""),
    [mode, setMode] = useState(""),
    [minimumScore, setMinimumScore] = useState(""),
    [filters, setFilters] = useState<FilterDraft[]>([]),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [result, setResult] = useState<Row | null>(null),
    [debug, setDebug] = useState(false);
  const applications = useData<Row[]>("/applications/available", []);
  return (
    <>
      <div className="page-heading">
        <div>
          <h1>检索调试</h1>
          <p>验证问题能否找到正确证据，对照召回与模型重排。</p>
        </div>
      </div>
      <form
        className="query-panel"
        onSubmit={async (e) => {
          e.preventDefault();
          setBusy(true);
          setError("");
          try {
            setResult(
              await post("/retrieval/search", {
                query,
                knowledge_base_ids: kb ? [kb] : [],
                application_id: appId || null,
                mode: mode || null,
                limit: 6,
                debug: true,
                minimum_rerank_score:
                  minimumScore === "" ? null : Number(minimumScore),
                filters: serializeFilters(filters),
              }),
            );
          } catch (e) {
            setError((e as Error).message);
            setResult(null);
          } finally {
            setBusy(false);
          }
        }}
      >
        <label>
          测试问题
          <div className="query-input">
            <MagnifyingGlass size={22} />
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              required
              placeholder="输入问题、产品型号或错误码…"
              maxLength={4000}
            />
            <button className="primary" disabled={busy}>
              {busy ? "检索中…" : "开始检索"}
              <ArrowRight />
            </button>
          </div>
        </label>
        <label>
          查询应用
          <select
            value={appId}
            onChange={(event) => {
              setAppId(event.target.value);
              setMode("");
              setResult(null);
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
        <RelevanceThreshold
          value={minimumScore}
          onChange={setMinimumScore}
          disabled={busy}
        />
        <MetadataFilterEditor
          value={filters}
          onChange={setFilters}
          disabled={busy}
        />
        <div className="query-options">
          <label>
            知识范围
            <select value={kb} onChange={(e) => setKb(e.target.value)}>
              <option value="">全部授权知识库</option>
              {k.data.map((x) => (
                <option value={x.id} key={x.id}>
                  {x.name}
                </option>
              ))}
            </select>
          </label>
          <label>
            检索策略
            <select value={mode} onChange={(e) => setMode(e.target.value)}>
              <option value="">使用已发布配置</option>
              <option value="hybrid">混合检索</option>
              <option value="semantic">语义检索</option>
              <option value="keyword">关键词检索</option>
            </select>
          </label>
        </div>
      </form>
      <ErrorNote error={error || k.error} />
      {busy ? (
        <Loading />
      ) : result ? (
        <>
          <div className="section-title">
            <h2>
              检索证据 <span className="count">{result.evidence.length}</span>
            </h2>
            <button onClick={() => setDebug(!debug)}>
              {" "}
              {debug ? "收起" : "查看"}调试详情
            </button>
          </div>
          {result.evidence_tokens != null && (
            <p>
              证据内容 {result.evidence_tokens} / {result.evidence_token_limit}{" "}
              Token（{result.evidence_tokenizer}）
            </p>
          )}
          {result.degraded && (
            <div className="notice">
              本次结果已降级，未完成全部模型处理阶段。
            </div>
          )}
          {!result.evidence.length ? (
            <Empty
              icon={MagnifyingGlass}
              title="没有找到可用证据"
              detail={
                result.evidence_status === "BELOW_THRESHOLD"
                  ? "已找到候选资料，但相关性未达到已配置的门槛。请补充问题信息。"
                  : result.evidence_status === "SCORE_UNAVAILABLE"
                    ? "评分服务不可用，无法确认资料相关性。请稍后重试或联系管理员。"
                    : "检查资料是否已发布、是否有访问权限，或调整问题表达。"
              }
            />
          ) : (
            result.evidence.map((c: Row, i: number) => (
              <article className="evidence" key={c.id}>
                <header>
                  <span className="rank">{i + 1}</span>
                  <b>{c.title}</b>
                  <code>{c.version_id.slice(0, 8)}</code>
                </header>
                <p>{c.content}</p>
                {c.context_kind && c.context_kind !== "CHUNK" && (
                  <small>
                    {c.context_kind === "NEIGHBORS"
                      ? "已补充同版本相邻片段"
                      : "已补充同版本父片段"}{" "}
                    · {c.token_count} Token
                  </small>
                )}
                <footer>
                  <FileText />
                  {evidenceSource(c)}
                </footer>
              </article>
            ))
          )}
          {debug && (
            <RetrievalTrace
              result={{
                ...result,
                trace_id: result.trace_id,
                publication_versions: result.publication_versions,
              }}
            />
          )}
        </>
      ) : (
        <Empty
          icon={MagnifyingGlass}
          title="用一个问题，检验你的知识"
          detail="检索仅返回知识证据，不调用生成模型。"
        />
      )}
      {result?.query_record_id &&
        ["NO_MATCH", "BELOW_THRESHOLD"].includes(result.evidence_status) && (
          <ImprovementRequest
            key={result.query_record_id}
            sourceKind="NO_RESULT"
            sourceId={result.query_record_id}
            defaultKb={kb}
          />
        )}
    </>
  );
}
