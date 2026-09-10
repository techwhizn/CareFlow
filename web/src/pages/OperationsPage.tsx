import { ErrorNote, Loading, useData } from "../ui";
type Count = {
  kind?: string;
  state?: string;
  request_state?: string;
  count: number;
};
type Status = {
  generated_at: string;
  jobs: Count[];
  cleanup: Count[];
  generation: Count[];
};
const labels: Record<string, string> = {
  PARSE: "资料解析",
  INDEX: "建立索引",
  QUEUED: "排队中",
  RUNNING: "执行中",
  DONE: "已完成",
  SUCCEEDED: "已完成",
  FAILED: "失败",
  CANCELLED: "已取消",
  RETRY: "等待重试",
  BLOCKED: "需要处理",
  PENDING: "待处理",
};
function Counts({ rows, jobs = false }: { rows: Count[]; jobs?: boolean }) {
  if (!rows.length) return <p>暂无记录</p>;
  return (
    <table>
      <thead>
        <tr>
          {jobs && <th>任务类型</th>}
          <th>状态</th>
          <th>数量</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row, index) => {
          const state = row.state || row.request_state || "";
          return (
            <tr key={index}>
              {jobs && <td>{labels[row.kind || ""] || row.kind}</td>}
              <td>{labels[state] || state}</td>
              <td>{row.count}</td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
export default function OperationsPage() {
  const status = useData<Status | null>("/operations/status", null);
  return (
    <>
      <div className="page-heading">
        <div>
          <h1>运行状态</h1>
          <p>当前企业的任务与处理状态汇总。</p>
        </div>
        <button onClick={() => void status.reload()}>刷新状态</button>
      </div>
      <ErrorNote error={status.error} />
      {status.loading ? (
        <Loading />
      ) : (
        status.data && (
          <>
            <p>
              统计时间：{new Date(status.data.generated_at).toLocaleString()}
            </p>
            <h2>处理任务</h2>
            <Counts rows={status.data.jobs} jobs />
            <h2>物理清理</h2>
            <Counts rows={status.data.cleanup} />
            <h2>回答生成</h2>
            <Counts rows={status.data.generation} />
            <p>
              运维角色仅查看汇总状态；内容、模型配置和访问凭证由对应管理员管理。
            </p>
          </>
        )
      )}
    </>
  );
}
