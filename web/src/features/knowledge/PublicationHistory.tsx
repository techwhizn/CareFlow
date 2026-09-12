import { ErrorNote, Loading, useData } from "../../ui";
type Publication = {
  id: string; version_id: string; actor_id: string; created_at: string;
  document_revision: number | null; version_revision: number | null; previous_version: string | null;
};
export default function PublicationHistory({documentId}:{documentId:string}) {
  const data=useData<Publication[]>(`/documents/${documentId}/publications`,[]);
  return <section><h2>发布记录</h2><ErrorNote error={data.error}/>
    {data.loading ? <Loading/> : data.data.length === 0 ? <p>尚无发布记录，处理就绪后仍需手动发布。</p> : <div className="table-wrap"><table>
      <thead><tr><th>时间</th><th>文档修订</th><th>发布版本</th><th>内容修订</th><th>上次版本</th><th>操作者</th></tr></thead>
      <tbody>{data.data.map(row=><tr key={row.id}>
        <td>{new Date(row.created_at).toLocaleString()}</td><td>{row.document_revision ?? "旧记录未采集"}</td>
        <td><code>{row.version_id}</code></td><td>{row.version_revision ?? "旧记录未采集"}</td>
        <td>{row.previous_version || "未记录"}</td><td>{row.actor_id}</td>
      </tr>)}</tbody>
    </table></div>}
    <p className="publication-note">展示最近100条有权访问的记录；回滚也是一次新发布，不恢复历史权限。</p>
  </section>;
}
