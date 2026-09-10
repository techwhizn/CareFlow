import { useState } from "react";
import { Empty, ErrorNote, Loading, useData } from "../../ui";
import { Stack } from "@phosphor-icons/react";

type Issue = {chunk_id: string; ordinal: number; page: number; codes: string[]};
type Report = {revision: number; chunks: number; enabled_chunks: number; tokens: number;
  distribution: Record<string, number>; warnings: Record<string, number>; issue_count: number;
  issues: Issue[]; has_more: boolean};
const labels: Record<string,string> = {
  EMPTY_CONTENT:"空内容", TOO_SHORT:"过短", TOO_LONG:"超出配置长度", DUPLICATE:"重复内容",
  INVALID_LOCATION:"来源位置异常", OCR_REVIEW:"OCR需核对", TEXT_ANOMALY:"异常字符",
  TABLE_HEADER_MISSING:"表头缺失", TABLE_HEADER_DUPLICATE:"表头重复", TABLE_ROW_BROKEN:"表格列数不符",
  TABLE_STRUCTURE_REVIEW:"复杂表格需核对", TABLE_ROW_SPLIT:"表格行被拆分", SOURCE_REVIEW:"原文需核对",
};

export default function QualityPanel({versionId, select}: {versionId:string;select:(page:number,id:string)=>void}) {
  const [page,setPage]=useState(0);
  const report=useData<Report|null>(`/document-versions/${versionId}/quality?page=${page}`,null);
  return <section className="panel">
    <div className="section-title"><h3>切片质量检查</h3><button onClick={()=>void report.reload()}>重新检查</button></div>
    <ErrorNote error={report.error}/>
    {report.loading ? <Loading/> : report.data && <>
      <p>内容修订 {report.data.revision} · 共 {report.data.chunks} 个切片 · 启用 {report.data.enabled_chunks} 个 · 启用内容 {report.data.tokens} Token</p>
      <p className="muted">检查不会自动删除或修改内容。重复采用空白归一后的完整文本比较；短片段和表格拆分提示需结合原文判断。</p>
      {!report.data.issue_count ? <Empty icon={Stack} title="未发现规则内的质量问题" detail="仍需人工核对原文、事实和表格含义。"/> : <>
        <p>{Object.entries(report.data.warnings).map(([code,count])=>`${labels[code]??code} ${count}`).join(" · ")}</p>
        <div className="button-row">{report.data.issues.map(issue=><button key={issue.chunk_id} onClick={()=>select(issue.page,issue.chunk_id)}>定位切片 {issue.ordinal+1}：{issue.codes.map(code=>labels[code]??code).join("、")}</button>)}</div>
        <div className="pagination"><button disabled={!page} onClick={()=>setPage(page-1)}>上一页问题</button><span>问题第 {page+1} 页</span><button disabled={!report.data.has_more} onClick={()=>setPage(page+1)}>下一页问题</button></div>
      </>}
    </>}
  </section>;
}
