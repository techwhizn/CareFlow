import { useState } from "react";
import type { Row } from "../api";
import { put } from "../api";
import { Dialog, ErrorNote, Loading, useData } from "../ui";

export default function KnowledgeLifecycle({ id, onChanged }: { id: string; onChanged: () => void }) {
  const [open, setOpen] = useState(false);
  return <div className="danger-area"><div><b>知识库生命周期</b><p>查看影响范围后归档、恢复或删除。</p></div>
    <button onClick={() => setOpen(true)}>查看影响与管理</button>
    {open && <LifecycleDialog id={id} close={() => setOpen(false)} onChanged={onChanged} />}
  </div>;
}
function LifecycleDialog({ id, close, onChanged }: { id: string; close: () => void; onChanged: () => void }) {
  const impact = useData<Row | null>(`/knowledge-bases/${id}/impact`, null);
  const [error, setError] = useState(""), [busy, setBusy] = useState(false), [confirm, setConfirm] = useState(false);
  async function change(status: string) {
    if (!impact.data || !confirm) return;
    if (status === "DELETED" && !window.confirm("永久删除此知识库？访问将立即停止，并登记物理清理请求。此操作不能通过恢复按钮撤销。")) return;
    setBusy(true); setError("");
    try { await put(`/knowledge-bases/${id}/state`, { status, revision: impact.data.revision }); close(); onChanged(); }
    catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  return <Dialog title="知识库状态变更影响" close={close}>
    <ErrorNote error={error || impact.error} />
    {impact.loading ? <Loading /> : impact.data && <>
      <p>当前状态：{impact.data.status === "ARCHIVED" ? "已归档" : "使用中"}</p>
      <p>当前可读文档 {impact.data.visible_documents}；本人依赖答案 {impact.data.own_dependent_answers}。</p>
      <p>引用应用：{impact.data.applications_visible ? impact.data.applications.map((a: Row) => a.name).join("、") || "无" : "无权查看应用关系"}</p>
      <p className="notice">{impact.data.scope_notice}</p>
      <p>归档立即停止新检索和正在处理的任务，相关历史答案隐藏。恢复保留既有权限，过期资料和未就绪版本仍不可检索；中断任务需要重新处理。</p>
      <label><input type="checkbox" checked={confirm} onChange={e => setConfirm(e.target.checked)} />我已核对影响范围</label>
      <div className="button-row">
        <button disabled={busy || !confirm} onClick={() => void change(impact.data!.status === "ARCHIVED" ? "ACTIVE" : "ARCHIVED")}>{impact.data.status === "ARCHIVED" ? "恢复知识库" : "归档知识库"}</button>
        <button className="danger" disabled={busy || !confirm} onClick={() => void change("DELETED")}>删除知识库</button>
        <button disabled={busy} onClick={() => { setConfirm(false); setError(""); void impact.reload(); }}>刷新影响范围</button>
      </div>
    </>}
  </Dialog>;
}
