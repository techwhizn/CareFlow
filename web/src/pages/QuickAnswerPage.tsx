import { PaperPlaneTilt, Stop, ChatCircleText } from "@phosphor-icons/react";
import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import type { Row } from "../api";
import { streamAnswer } from "../api";
import { Empty, ErrorNote, Loading, useData } from "../ui";

function answerText(value: string) {
  return value.replace(/\s*\[[0-9a-f]{8}-[0-9a-f-]{27,}\]/gi, "").trim();
}

/** A low-friction entry point for non-technical users: ask, summarize, verify sources. */
export default function QuickAnswerPage() {
  const knowledge = useData<Row[]>("/knowledge-bases", []);
  const [kb, setKb] = useState("");
  const [query, setQuery] = useState("");
  const [answer, setAnswer] = useState("");
  const [citations, setCitations] = useState<Row[]>([]);
  const [error, setError] = useState("");
  const [stage, setStage] = useState("");
  const [busy, setBusy] = useState(false);
  const [source, setSource] = useState<string | null>(null);
  const controller = useRef<AbortController | null>(null);
  useEffect(() => {
    if (!kb && knowledge.data.length === 1) setKb(knowledge.data[0].id);
  }, [kb, knowledge.data]);
  async function submit(event: FormEvent) {
    event.preventDefault();
    controller.current?.abort();
    const control = new AbortController();
    controller.current = control;
    setBusy(true); setError(""); setAnswer(""); setCitations([]); setSource(null); setStage("正在检索知识库…");
    try {
      await streamAnswer({ query, knowledge_base_ids: kb ? [kb] : [], mode: null, limit: 6, debug: false }, (name, data) => {
        if (control.signal.aborted) return;
        if (name === "status") setStage(data.stage === "generation" ? "正在生成答案…" : "正在检索知识库…");
        if (name === "delta") setAnswer((value) => value + data.text);
        if (name === "citations") setCitations(data.evidence || []);
        if (name === "error") setError(data.message);
        if (name === "done") setStage("已完成");
      }, control.signal);
    } catch (cause) {
      if ((cause as Error).name !== "AbortError") setError((cause as Error).message);
    } finally { if (controller.current === control) setBusy(false); }
  }
  return <>
    <div className="page-heading"><div><h1>知识库问答</h1><p>输入一个问题，系统会根据已发布知识整理总结，并标注来源。</p></div></div>
    <form className="quick-answer-panel panel" onSubmit={submit}>
      <label>知识库<select value={kb} disabled={busy} onChange={(event) => setKb(event.target.value)}><option value="">全部授权知识库</option>{knowledge.data.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
      <label>你想了解什么？<textarea rows={4} required maxLength={4000} value={query} disabled={busy} onChange={(event) => setQuery(event.target.value)} placeholder="例如：张福亮工作几年了？" /></label>
      <div className="quick-answer-actions"><span className="muted">{stage || "回答会严格依据知识库内容"}</span>{busy ? <button type="button" onClick={() => controller.current?.abort()}><Stop />停止</button> : <button className="primary"><PaperPlaneTilt />生成答案</button>}</div>
    </form>
    <ErrorNote error={error || knowledge.error} />
    {knowledge.loading ? <Loading /> : answer ? <section className="quick-answer-result panel"><h2><ChatCircleText />答案</h2><div className="answer-content">{answerText(answer)}</div>{citations.length > 0 && <><h3>依据来源</h3><div className="quick-citations">{citations.map((item) => { const link = typeof item.source === "string" && /^https?:\/\//i.test(item.source) ? item.source : ""; return <div key={item.id}><button type="button" className="citation-button" onClick={() => setSource(source === item.id ? null : item.id)}>{item.title || "知识库文档"} · {link ? "打开来源" : item.location ? "查看原文定位" : "查看引用"}</button>{source === item.id && <div className="citation-detail"><b>{item.title || "知识库文档"}</b>{link && <a className="citation-link" href={link} target="_blank" rel="noreferrer">打开来源链接 ↗</a>}<small>{item.location || "原文位置未提供"}</small><p>{item.matched_content || item.content}</p></div>}</div>; })}</div></>}</section> : <Empty icon={ChatCircleText} title="开始一次知识库问答" detail="系统会检索相关内容并直接给出答案。" />}
  </>;
}
