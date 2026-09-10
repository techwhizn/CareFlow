import { useEffect, useState } from "react";
import { ApiError, put, request } from "../api";
import { Dialog, ErrorNote, Loading } from "../ui";

type Snapshot = {revision: number; restricted: boolean; grants: {subject_id:string;action:string}[]; subjects: {id:string;name:string;kind:string;active:boolean}[]};
const actions = [["read","读取"],["download","下载"],["edit","编辑"],["publish","发布"],["manage","管理"]];
export default function AclDialog({resource,type,close,onSaved}:{resource:{id:string};type:string;close:()=>void;onSaved:()=>void}) {
  const [snapshot,setSnapshot]=useState<Snapshot|null>(null);
  const [grants,setGrants]=useState<Record<string,string[]>>({});
  const [error,setError]=useState("");
  const [busy,setBusy]=useState(false);
  const [conflict,setConflict]=useState(false);
  const [confirmed,setConfirmed]=useState(false);
  async function load() {
    setBusy(true);setError("");
    try {const value=await request<Snapshot>(`/${type}/${resource.id}/authorization`);
      const next:Record<string,string[]>={};for(const grant of value.grants) (next[grant.subject_id]??=[]).push(grant.action);
      setSnapshot(value);setGrants(next);setConflict(false);setConfirmed(false);
    } catch(error){setError((error as Error).message);} finally{setBusy(false);}
  }
  useEffect(()=>{void load();},[resource.id,type]);
  return <Dialog title="资源授权" close={close}>
    <ErrorNote error={error}/>
    {conflict && <button type="button" onClick={()=>void load()}>重新加载最新授权（放弃本地修改）</button>}
    {busy && <Loading/>}
    {snapshot && <form onSubmit={async event=>{
      event.preventDefault();if(!confirmed || conflict)return;setBusy(true);setError("");
      try {await put(`/${type}/${resource.id}/permissions`,{revision:snapshot.revision,grants});onSaved();close();}
      catch(error){setError((error as Error).message);if(error instanceof ApiError && error.status===409)setConflict(true);}
      finally{setBusy(false);}
    }}>
      <div className="notice">已加载现有授权。保存将整体替换显式授权；未勾选的权限会撤销。文档授权只能收窄知识库权限，且可能使当前操作者失去文档管理权。</div>
      <fieldset disabled={busy || conflict}><legend>成员与应用权限</legend>
        {snapshot.subjects.map(subject=><div key={subject.id}>
          <b>{subject.name} · {subject.kind}{!subject.active?"（已禁用）":""}</b>
          <div className="query-options">{actions.map(([action,label])=><label key={action}>
            <input type="checkbox" checked={(grants[subject.id]||[]).includes(action)} onChange={event=>{
              const values=grants[subject.id]||[];
              setGrants({...grants,[subject.id]:event.target.checked?[...values,action]:values.filter(value=>value!==action)});setConfirmed(false);
            }}/>{label}
          </label>)}</div>
        </div>)}
        {Object.keys(grants).filter(id=>!snapshot.subjects.some(subject=>subject.id===id)).map(id=><p key={id}>已失效主体 {id} <button type="button" onClick={()=>{const next={...grants};delete next[id];setGrants(next);setConfirmed(false);}}>移除失效授权</button></p>)}
        <label><input type="checkbox" checked={confirmed} onChange={event=>setConfirmed(event.target.checked)}/>已核对所有主体，确认替换后的授权范围</label>
        <button className="primary" disabled={!confirmed}>保存授权</button>
      </fieldset>
    </form>}
  </Dialog>;
}
