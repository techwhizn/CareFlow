import { Empty, ErrorNote, Loading, useData } from "../../ui";
import { Stack } from "@phosphor-icons/react";

type Report = {revision: number; chunks: number; enabled_chunks: number; tokens: number;
  distribution: Record<string, number>; issue_count: number};

export default function QualityPanel({versionId}: {versionId:string}) {
  const report=useData<Report|null>(`/document-versions/${versionId}/quality`,null);
  return <section className="panel">
    <div className="section-title"><h3>切片质量检查</h3><button onClick={()=>void report.reload()}>重新检查</button></div>
    <ErrorNote error={report.error}/>
    {report.loading ? <Loading/> : report.data && <>
      <p>内容修订 {report.data.revision} · 共 {report.data.chunks} 个切片 · 启用 {report.data.enabled_chunks} 个 · 启用内容 {report.data.tokens} Token</p>
      {Object.entries(report.data.distribution).some(([, count]) => count > 0) ? <table aria-label="切片长度分布"><thead><tr><th>逻辑 Token 区间</th><th>切片数量</th></tr></thead><tbody>{Object.entries(report.data.distribution).filter(([, count]) => count > 0).map(([range,count])=><tr key={range}><td>{range}</td><td>{count}</td></tr>)}</tbody></table> : <p className="muted">暂无切片长度分布数据。</p>}
      <p>预计完整重建处理 {report.data.enabled_chunks} 个切片、{report.data.tokens} 个逻辑Token。</p>
      <p className="muted">这是当前启用内容的处理规模估算，包含标题与表头；不代表模型账单、处理耗时或金额。模型原生Token和增量复用数量以执行记录为准。</p>
      <p className="muted">检查不会自动删除或修改内容。重复采用空白归一后的完整文本比较；短片段和表格拆分提示需结合原文判断。</p>
      {!report.data.issue_count ? <Empty icon={Stack} title="未发现规则内的质量问题" detail="仍需人工核对原文、事实和表格含义。"/> : <>
        <div className="quality-review-guide">
          <strong>发现 {report.data.issue_count} 个待核对项目</strong>
          <p>核对标准：对照左侧原文确认内容、顺序和表格含义。发现错误时，点击右侧切片后选择“编辑选中切片”；确认无误的切片无需额外标记，重新索引并发布后即完成本轮核对。</p>
          <button className="text-button" type="button" onClick={() => document.getElementById("chunk-source-preview")?.scrollIntoView({ behavior: "smooth", block: "start" })}>开始核对切片</button>
        </div>
      </>}
    </>}
  </section>;
}
