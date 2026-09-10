import { useState } from "react";
import type { Row } from "../../api";
import { post, put } from "../../api";
import { Dialog, ErrorNote, Loading, useData } from "../../ui";

type Context = { id: string; kind: "FAQ" | "PARENT"; content: string; question: string | null; answer: string | null; alternatives_json: string; origin: string; token_count: number };

export function FaqForm({ version, initial, close, saved }: { version: Row; initial?: Context; close: () => void; saved: () => Promise<void> }) {
  const [busy,setBusy]=useState(false),[error,setError]=useState("");
  const alternatives: string[] = initial ? JSON.parse(initial.alternatives_json) : [];
  return <Dialog title={initial ? "修订整组FAQ" : "人工补充FAQ"} close={close}>
    <ErrorNote error={error}/>
    <form onSubmit={async event=>{
      event.preventDefault();setBusy(true);setError("");const data=new FormData(event.currentTarget);
      const body={revision:version.revision,question:String(data.get("question")),answer:String(data.get("answer")),
        alternatives:String(data.get("alternatives")).split("\n").map(s=>s.trim()).filter(Boolean),reason:String(data.get("reason"))};
      try {
        const path=`/document-versions/${version.id}/faqs`;
        if(initial)await put(`${path}/${initial.id}`,body);else await post(path,body);
        await saved();close();
      }catch(cause){setError((cause as Error).message);}finally{setBusy(false);}
    }}>
      <label>问题<input name="question" required maxLength={1000} defaultValue={initial?.question ?? ""}/></label>
      <label>相似问法（每行一条，最多20条）<textarea name="alternatives" rows={3} defaultValue={alternatives.join("\n")}/></label>
      <label>答案<textarea name="answer" required maxLength={8000} rows={8} defaultValue={initial?.answer ?? ""}/></label>
      <label>修改原因<input name="reason" required maxLength={1000}/></label>
      <p className="muted">保存整组关系并重新切分答案。内容标为人工补充或修订，不伪造原文件定位；重新索引和文档发布后才对线上生效。</p>
      <button disabled={busy} className="primary">保存FAQ</button>
    </form>
  </Dialog>;
}

export default function ContextPanel({ version, contextId, saved }: {version: Row; contextId: string; saved:()=>Promise<void>}) {
  const context=useData<Context|null>(`/document-versions/${version.id}/contexts/${contextId}`,null);
  const [editing,setEditing]=useState(false),[detaching,setDetaching]=useState(false),[error,setError]=useState("");
  return <section className="panel">
    <ErrorNote error={context.error || error}/>
    {context.loading?<Loading/>:context.data && <>
      <h3>{context.data.kind==="FAQ"?"关联FAQ":"父片段上下文"}</h3>
      <p className="muted">{context.data.token_count} Token · {context.data.origin==="MANUAL"?"人工补充或修订，无原文件定位":"从同一内容版本提取"}</p>
      <pre style={{whiteSpace:"pre-wrap"}}>{context.data.content}</pre>
      {!version.ever_published && <div className="button-row">
        {context.data.kind==="FAQ" && <button onClick={()=>setEditing(true)}>修订整组FAQ</button>}
        <button onClick={()=>setDetaching(true)}>解除该组关联后单独编辑</button>
      </div>}
      {editing && <FaqForm version={version} initial={context.data} close={()=>setEditing(false)} saved={async()=>{await saved();await context.reload();}}/>}
      {detaching && <Dialog title="解除分组关联" close={()=>setDetaching(false)}><form onSubmit={async event=>{
        event.preventDefault();const data=new FormData(event.currentTarget);setError("");
        try{await post(`/document-versions/${version.id}/contexts/${contextId}/detach`,{revision:version.revision,reason:String(data.get("reason"))});await saved();setDetaching(false);}catch(cause){setError((cause as Error).message);}
      }}><p>保留每个检索切片，但这组切片不再补充原父片段或FAQ上下文。</p><label>原因<input name="reason" required maxLength={1000}/></label><button>确认解除关联</button></form></Dialog>}
    </>}
  </section>;
}
