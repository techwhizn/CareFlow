import { useState } from "react";
import { UploadSimple } from "@phosphor-icons/react";
import { request } from "../api";
import { ErrorNote } from "../ui";

type Item = { name: string; state: string; detail: string };
export default function DocumentUpload({ knowledgeBaseId, onUploaded }: {knowledgeBaseId: string; onUploaded: () => Promise<void>}) {
  const [items, setItems] = useState<Item[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function upload(files: File[]) {
    if (!files.length || busy) return;
    if (files.length>20) {setError("单批最多20个文件，本批未上传。"); return;}
    setBusy(true); setError("");
    setItems(files.map(file=>({name:file.name,state:"排队",detail:""})));
    function update(index:number,state:string,detail="") {setItems(previous=>previous.map((item,i)=>i===index?{...item,state,detail}:item));}
    try {
      for(let index=0;index<files.length;index++) {
        const file=files[index];
        if(file.size===0 || file.size>50*1024*1024) {update(index,"失败","文件为空或超过50MiB"); continue;}
        update(index,"上传中");
        const data=new FormData();data.append("file",file);
        try {
          await request(`/knowledge-bases/${knowledgeBaseId}/documents`,{method:"POST",body:data});
          update(index,"已接收","等待解析；不代表处理完成或已发布");
        } catch(error) {update(index,"失败",(error as Error).message);}
      }
      await onUploaded();
    } catch(error) {setError((error as Error).message);}
    finally {setBusy(false);}
  }
  return <>
    <ErrorNote error={error}/>
    <label className={`upload-zone ${busy?"disabled":""}`}>
      <UploadSimple size={28}/><div><strong>{busy?"逐个上传中…":"选择资料文件"}</strong>
      <p>PDF、DOCX、Markdown、TXT、CSV、XLSX、PNG、JPEG · 单文件50MiB · 每批最多20个</p></div>
      <input type="file" multiple disabled={busy} accept=".pdf,.docx,.md,.txt,.csv,.xlsx,.png,.jpg,.jpeg" onChange={event=>{
        const files=Array.from(event.target.files||[]);event.target.value="";void upload(files);
      }}/>
    </label>
    {items.length>0 && <div aria-live="polite"><h3>本批上传结果</h3>{items.map((item,index)=><p key={index}><b>{item.name}</b> · {item.state} {item.detail}</p>)}</div>}
  </>;
}
