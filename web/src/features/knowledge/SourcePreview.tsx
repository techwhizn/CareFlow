import type { Row } from "../../api";
import { sourceLabels } from "./sourceLabels";

export default function SourcePreview({chunk,location}: {chunk:Row;location:Record<string,unknown>}) {
  const labels=sourceLabels(location,chunk.origin==="MANUAL");
  return <div aria-live="polite">
    <p className="source-position">{labels.length?labels.join(" · "):"来源定位不可用，请下载原文核对"}</p>
    {chunk.origin==="MANUAL" ? <p className="notice">此内容由人工补充或修订，不对应原文件中的位置。</p> : <>
      {chunk.origin==="MANUAL_EDIT" && <p className="muted">下方保留修订前的原始提取内容，右侧为当前检索内容。</p>}
      {location.type==="table" && <p className="muted">表格按行提取；列标签中的单位保留。复杂合并关系请下载原文件核对。</p>}
      <pre className="source-selected"><mark>{chunk.source_text}</mark></pre>
    </>}
    <details className="source-location"><summary>查看来源记录</summary><pre>{JSON.stringify(location,null,2)}</pre></details>
  </div>;
}
