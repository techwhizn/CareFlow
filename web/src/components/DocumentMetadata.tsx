import { useState } from "react";
import type { Row } from "../api";
import { put } from "../api";
import { Dialog, ErrorNote, Loading, useData } from "../ui";

export default function DocumentMetadata({ id, onSaved }: { id: string; onSaved: () => void }) {
  const data = useData<Row | null>(`/documents/${id}/metadata`, null);
  const [editing, setEditing] = useState(false), [showHistory, setShowHistory] = useState(false);
  const [error, setError] = useState(""), [busy, setBusy] = useState(false);
  const m = data.data;
  return <section className="panel">
    <div className="section-title"><h2>文档元数据</h2><div className="button-row">
      <button onClick={() => setEditing(true)}>编辑元数据与有效期</button>
      <button onClick={() => setShowHistory(true)}>元数据修订记录</button>
    </div></div>
    <ErrorNote error={data.error} />
    {data.loading ? <Loading /> : m && <>
      <p>来源：{m.source || "未填写"} · 语言：{m.language}</p>
      <p>标签：{m.tags.join("、") || "未填写"} · 产品型号：{m.product_models.join("、") || "未填写"}</p>
      <p>有效期：{m.valid_from ? new Date(m.valid_from).toLocaleString() : "不限开始时间"} 至 {m.valid_until ? new Date(m.valid_until).toLocaleString() : "不限结束时间"}（显示为本地时区）</p>
    </>}
    {editing && m && <Dialog title="编辑文档元数据" close={() => setEditing(false)}>
      <ErrorNote error={error} />
      <form key={m.revision} onSubmit={async e => {
        e.preventDefault(); setBusy(true); setError(""); const form = new FormData(e.currentTarget);
        const list = (key: string) => String(form.get(key)).split(/[,，]/).map(s => s.trim()).filter(Boolean);
        try {
          await put(`/documents/${id}/metadata`, { title: form.get("title"), source: form.get("source"),
            language: form.get("language"), tags: list("tags"), product_models: list("product_models"),
            valid_from: form.get("valid_from") || null, valid_until: form.get("valid_until") || null, revision: m.revision });
          setEditing(false); onSaved();
        } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
      }}>
        <label>标题<input name="title" required maxLength={250} defaultValue={m.title} /></label>
        <label>来源（文字或地址，仅保存，不自动抓取）<input name="source" maxLength={2000} defaultValue={m.source} /></label>
        <label>语言<input name="language" required maxLength={20} defaultValue={m.language} /></label>
        <label>标签（逗号分隔，最多20个）<input name="tags" defaultValue={m.tags.join(", ")} /></label>
        <label>产品型号（逗号分隔，最多50个）<input name="product_models" defaultValue={m.product_models.join(", ")} /></label>
        <label>生效时间（带时区，留空不限制）<input name="valid_from" defaultValue={m.valid_from || ""} placeholder="2026-09-10T09:00:00+08:00" /></label>
        <label>失效时间（带时区，留空不限制）<input name="valid_until" defaultValue={m.valid_until || ""} placeholder="2027-09-10T09:00:00+08:00" /></label>
        <p className="notice">有效期修改立即影响检索及引用交付；未生效或已过期资料不参与默认检索。原文件和已发布切片保持原内容。</p>
        <div className="button-row"><button className="primary" disabled={busy}>保存元数据</button>
          <button type="button" disabled={busy} onClick={() => { if (window.confirm("重新加载将丢弃本地修改，是否继续？")) { setError(""); void data.reload(); } }}>重新加载最新元数据</button></div>
      </form>
    </Dialog>}
    {showHistory && <MetadataHistory id={id} close={() => setShowHistory(false)} />}
  </section>;
}
function MetadataHistory({ id, close }: { id: string; close: () => void }) {
  const [page, setPage] = useState(0);
  const history = useData<Row[]>(`/documents/${id}/metadata-history?page=${page}`, []);
  return <Dialog title="元数据修订记录" close={close}>
    <ErrorNote error={history.error} />
    {history.loading ? <Loading /> : !history.data.length ? <p>暂无元数据修订。</p> : history.data.map(h =>
      <details key={h.revision}><summary>修订 {h.revision} · {new Date(h.created_at).toLocaleString()} · 操作者 {h.actor_id}</summary>
        <pre style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>{JSON.stringify(JSON.parse(h.metadata_json), null, 2)}</pre></details>)}
    <div className="pagination"><button disabled={!page} onClick={() => setPage(page - 1)}>上一页</button>
      <span>第 {page + 1} 页</span><button disabled={history.data.length < 50} onClick={() => setPage(page + 1)}>下一页</button></div>
  </Dialog>;
}
