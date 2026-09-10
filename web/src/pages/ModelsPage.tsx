import { useState } from "react";
import { post, put } from "../api";
import { ErrorNote, Loading, useData } from "../ui";

type Profile = {
  id: string; name: string; kind: string; base_url: string; model: string;
  model_revision: string; dimensions: number | null; external_processing: boolean;
  key_configured: boolean; revision: number;
};
type Policy = { external_bases: string[]; local_bases: string[] };
const blank = { id: "", name: "", kind: "GENERATION", base_url: "", model: "",
  model_revision: "", dimensions: "", external_processing: true, revision: 0 };

export default function ModelsPage() {
  const profiles = useData<Profile[]>("/model-profiles", []);
  const policy = useData<Policy>("/model-profiles/policy", {external_bases: [], local_bases: []});
  const [form, setForm] = useState(blank);
  const [key, setKey] = useState("");
  const [clearKey, setClearKey] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const bases = form.external_processing ? policy.data.external_bases : policy.data.local_bases;
  function reset() {setForm(blank); setKey(""); setClearKey(false);}
  return <>
    <div className="page-heading"><div><h1>模型配置</h1><p>管理生成、向量化与重排服务，仅企业所有者和管理员可操作。</p></div></div>
    <div className="notice">此处保存模型连接配置。知识库配置绑定与连接检测尚待后续接入，保存不会切换当前运行模型。</div>
    <ErrorNote error={error || profiles.error || policy.error} />
    {notice && <div className="notice" role="status">{notice}</div>}
    <form className="query-panel" onSubmit={async event => {
      event.preventDefault(); setBusy(true); setError(""); setNotice("");
      const body = {...form, dimensions: form.kind === "EMBEDDING" ? Number(form.dimensions) : null,
        api_key: clearKey ? "" : key || null};
      try {
        if (form.id) await put(`/model-profiles/${form.id}`, body);
        else await post("/model-profiles", body);
        reset(); setNotice("配置已保存；密钥不会回显。"); await profiles.reload();
      } catch (error) {setError((error as Error).message);}
      finally {setKey(""); setBusy(false);}
    }}>
      <fieldset disabled={busy}>
        <legend>{form.id ? "编辑模型配置" : "新增模型配置"}</legend>
        <div className="query-options">
          <label>名称<input required maxLength={200} value={form.name} onChange={e=>setForm({...form,name:e.target.value})}/></label>
          <label>类型<select value={form.kind} onChange={e=>setForm({...form,kind:e.target.value})}>
            <option value="GENERATION">生成</option><option value="EMBEDDING">向量化</option><option value="RERANK">重排</option>
          </select></label>
          <label>数据处理范围<select value={String(form.external_processing)} onChange={e=>setForm({...form,external_processing:e.target.value==="true",base_url:""})}>
            <option value="true">允许发送到外部模型</option><option value="false">仅使用已批准的自托管服务</option>
          </select></label>
          <label>已批准的服务地址<select required value={form.base_url} onChange={e=>setForm({...form,base_url:e.target.value})}>
            <option value="">请选择服务地址</option>{bases.map(base=><option key={base} value={base}>{base}</option>)}
          </select></label>
          <label>模型名称<input required maxLength={200} value={form.model} onChange={e=>setForm({...form,model:e.target.value})}/></label>
          <label>模型版本<input required={form.kind==="EMBEDDING"} maxLength={200} value={form.model_revision} onChange={e=>setForm({...form,model_revision:e.target.value})}/></label>
          {form.kind==="EMBEDDING" && <label>向量维度<input type="number" required min={1} max={65536} value={form.dimensions} onChange={e=>setForm({...form,dimensions:e.target.value})}/></label>}
          <label>API Key<input type="password" autoComplete="new-password" maxLength={8192} value={key} disabled={clearKey} onChange={e=>setKey(e.target.value)} placeholder={form.id ? "留空保留现有密钥" : "无需密钥时可留空"}/></label>
        </div>
        {form.id && <label><input type="checkbox" checked={clearKey} onChange={e=>setClearKey(e.target.checked)}/>清除现有密钥（仅用于无需认证的服务）</label>}
        <p>更换服务地址或类型时需重新填写密钥。地址由部署管理员批准；外部模型会接收检索内容或问题。</p>
        <button className="primary" type="submit">{busy ? "保存中…" : "保存配置"}</button>{form.id && <button type="button" onClick={reset}>取消编辑</button>}
      </fieldset>
    </form>
    {busy && <Loading/>}
    <div className="section-title"><h2>已保存配置</h2></div>
    {profiles.data.length===0 && <p>暂无可见模型配置。</p>}
    {profiles.data.map(profile=><article className="evidence" key={profile.id}>
      <header><b>{profile.name}</b><span>{profile.kind}</span></header>
      <p>{profile.model} · {profile.base_url}</p><p>{profile.key_configured ? "密钥已配置" : "未配置密钥"} · 版本 {profile.revision}</p>
      <button disabled={busy} onClick={()=>{setForm({...profile,dimensions:profile.dimensions===null?"":String(profile.dimensions)});setKey("");setClearKey(false);setNotice("");}}>编辑</button>
    </article>)}
  </>;
}
