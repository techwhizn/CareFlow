import { useState } from "react";
import type { Row } from "../api";
import { put } from "../api";
import { Dialog, ErrorNote, Loading, useData } from "../ui";

export default function KnowledgeOverview({ id, onSaved }: { id: string; onSaved: () => void }) {
  const overview = useData<Row | null>(`/knowledge-bases/${id}/overview`, null);
  const [editing, setEditing] = useState(false);
  const value = overview.data;
  return <section className="panel">
    <div className="section-title"><h2>知识库概览</h2><div className="button-row">
      <button onClick={() => void overview.reload()}>刷新概览</button>
      <button onClick={() => setEditing(true)}>编辑属性与责任人</button>
    </div></div>
    <ErrorNote error={overview.error} />
    {overview.loading ? <Loading /> : value && <>
      <p>可访问文档 {value.document_count} · 已发布有效切片 {value.effective_chunk_count}
        {value.failed_job_count !== null && ` · 可管理文档失败任务 ${value.failed_job_count}`}</p>
      <p>可访问原文件 {(value.known_source_bytes / 1048576).toFixed(2)} MiB
        {value.unknown_source_objects > 0 && `，另有 ${value.unknown_source_objects} 个历史对象大小未知`}</p>
      <p className="muted">统计遵守当前权限；原文件按对象去重，包含可查看的历史版本，不包含索引与备份空间。</p>
      <h3>引用此知识库的已绑定应用</h3>
      {!value.applications_visible ? <p className="muted">需要应用管理权限查看。</p> : value.applications.length ?
        <ul>{value.applications.map((a: Row) => <li key={a.id}>{a.name}</li>)}</ul> : <p className="muted">暂无已绑定应用。</p>}
    </>}
    {editing && <KnowledgeSettings id={id} close={() => setEditing(false)} onSaved={onSaved} />}
  </section>;
}

function KnowledgeSettings({ id, close, onSaved }: { id: string; close: () => void; onSaved: () => void }) {
  const settings = useData<Row | null>(`/knowledge-bases/${id}/settings`, null);
  const [error, setError] = useState(""), [busy, setBusy] = useState(false);
  const kb = settings.data?.knowledge_base;
  return <Dialog title="知识库属性与责任人" close={close}>
    <ErrorNote error={error || settings.error} />
    {settings.loading ? <Loading /> : kb && <form key={kb.revision} onSubmit={async e => {
      e.preventDefault(); const form = new FormData(e.currentTarget); setBusy(true); setError("");
      const owner = String(form.get("owner_id"));
      if (owner !== kb.owner_id && !window.confirm("移交责任人会授予新责任人知识库管理权限，并移除原责任人的隐式权限（显式授权保留）。确认移交？")) { setBusy(false); return; }
      try {
        await put(`/knowledge-bases/${id}`, { name: form.get("name"), description: form.get("description"),
          language: form.get("language"), tags: String(form.get("tags")).split(/[,，]/).map(x => x.trim()).filter(Boolean),
          owner_id: owner, revision: kb.revision });
        close(); onSaved();
      } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
    }}>
      <label>名称<input name="name" required maxLength={200} defaultValue={kb.name} /></label>
      <label>描述<textarea name="description" maxLength={2000} defaultValue={kb.description} /></label>
      <label>语言<input name="language" required maxLength={20} defaultValue={kb.language} placeholder="zh、en、zh-CN" /></label>
      <label>标签（逗号分隔，最多20个，每个50字）<input name="tags" defaultValue={kb.tags.join(", ")} /></label>
      <label>责任人<select name="owner_id" defaultValue={kb.owner_id} required>
        {!settings.data!.owners.some((m: Row) => m.id === kb.owner_id) && <option value={kb.owner_id} disabled>当前责任人已停用，请重新选择</option>}
        {settings.data!.owners.map((m: Row) => <option key={m.id} value={m.id}>{m.name} · {m.role}</option>)}
      </select></label>
      <p className="muted">属性修订不会重新解析或重建索引。冲突时保留当前表单；核对最新属性后再修改。</p>
      <div className="button-row"><button className="primary" disabled={busy}>保存属性</button>
        <button type="button" disabled={busy} onClick={() => {
          if (window.confirm("重新加载会丢弃本地未保存修改，是否继续？")) { setError(""); void settings.reload(); }
        }}>重新加载最新属性</button></div>
    </form>}
  </Dialog>;
}
