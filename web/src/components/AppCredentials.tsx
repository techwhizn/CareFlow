import { useState } from "react";
import { post, request } from "../api";
import { ErrorNote, useData } from "../ui";

type Credential = {id:string;active:boolean;expires_at:string;scopes:string};
export default function AppCredentials({applicationId,onIssued}:{applicationId:string;onIssued:(token:string)=>void}) {
  const keys=useData<Credential[]>(`/applications/${applicationId}/credentials`,[]);
  const [scopes,setScopes]=useState(["SEARCH","ANSWER"]);
  const [days,setDays]=useState(90);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState("");
  return <section>
    <h3>应用 API Key</h3><ErrorNote error={error||keys.error}/>
    <form onSubmit={async event=>{
      event.preventDefault();setBusy(true);setError("");
      try {const result=await post(`/applications/${applicationId}/credentials`,{scopes,expires_in_days:days});onIssued(result.token);await keys.reload();}
      catch(error){setError((error as Error).message);} finally{setBusy(false);}
    }}>
      <fieldset disabled={busy}><legend>新密钥作用范围</legend>
        {[["READ","读取元数据、历史与授权原文"],["SEARCH","搜索"],["ANSWER","问答"]].map(([scope,label])=><label key={scope}>
          <input type="checkbox" checked={scopes.includes(scope)} onChange={event=>setScopes(event.target.checked?[...scopes,scope]:scopes.filter(value=>value!==scope))}/>{label}
        </label>)}
        <label>有效天数<input type="number" min={1} max={365} required value={days} onChange={event=>setDays(Number(event.target.value))}/></label>
        <button disabled={scopes.length===0}>生成 API Key</button>
      </fieldset>
    </form>
    <p>轮换时先签发新密钥并迁移调用方，验证成功后撤销旧密钥。密钥不能绕过应用绑定和资源权限。</p>
    {keys.data.map(key=><div className="section-title" key={key.id}>
      <span><code>{key.id.slice(0,8)}</code> · {key.scopes} · {key.active?"有效":"已撤销"} · {new Date(key.expires_at).toLocaleString()}</span>
      {key.active && <button disabled={busy} onClick={async()=>{
        if(!window.confirm("撤销后使用此密钥的请求立即失败，确认撤销？"))return;
        setBusy(true);setError("");
        try {await request(`/applications/${applicationId}/credentials/${key.id}`,{method:"DELETE"});await keys.reload();}
        catch(error){setError((error as Error).message);}finally{setBusy(false);}
      }}>撤销</button>}
    </div>)}
  </section>;
}
