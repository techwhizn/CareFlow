import {
  ArrowsClockwise,
  CheckCircle,
  ClockCounterClockwise,
  Pulse,
  WarningCircle,
} from "@phosphor-icons/react";
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
  RECOVERED: "超时回收",
  RETRY: "等待重试",
  BLOCKED: "需要处理",
  PENDING: "待处理",
};
function Counts({ rows, jobs = false }: { rows: Count[]; jobs?: boolean }) {
  if (!rows.length) return <p className="ops-empty">暂无记录</p>;
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
const metricLabels: Record<string, string> = {
  careflow_jobs_queued: "排队任务",
  careflow_jobs_running: "运行任务",
  careflow_jobs_failed: "失败任务",
  careflow_jobs_expired_lease: "租约已过期任务",
  careflow_queries_reserved: "查询预占",
  careflow_queries_expired: "待回收查询",
  careflow_queries_failed_recent: "近五分钟失败查询",
  careflow_cleanup_blocked: "受阻清理任务",
  QUERY_FAILURE_BURST: "近五分钟查询失败达到告警阈值",
};
export default function OperationsPage() {
  const metrics = useData<{
    gauges: Record<string, number>;
    alerts: { code: string; count: number }[];
  } | null>("/operations/metrics", null);
  const status = useData<Status | null>("/operations/status", null);
  return (
    <>
      <div className="page-heading">
        <div>
          <span className="eyebrow">OPERATIONS CENTER</span>
          <h1>运行状态</h1>
          <p>当前企业的任务与处理状态汇总。</p>
        </div>
        <button
          className="ops-refresh"
          onClick={() => {
            void status.reload();
            void metrics.reload();
          }}
        >
          <ArrowsClockwise size={16} />
          刷新状态
        </button>
      </div>
      <ErrorNote error={status.error || metrics.error} />
      {metrics.data && <section className="ops-summary">
        <div className="ops-summary-head">
          <div>
            <h2>运行指标</h2>
            <p>实时查看任务、查询和清理状态。</p>
          </div>
          <span className={metrics.data.alerts.length ? "ops-status is-alert" : "ops-status"}>
            {metrics.data.alerts.length ? <WarningCircle size={15} /> : <CheckCircle size={15} />}
            {metrics.data.alerts.length ? `${metrics.data.alerts.length} 项需要关注` : "运行正常"}
          </span>
        </div>
        <div className="ops-metric-grid">
          {Object.entries(metrics.data.gauges).map(([key, value]) => (
            <article className="ops-metric" key={key}>
              <div className="ops-metric-icon"><Pulse size={18} /></div>
              <div><dt>{metricLabels[key] || key}</dt><dd>{value}</dd></div>
            </article>
          ))}
        </div>
        <div className={metrics.data.alerts.length ? "ops-alerts is-alert" : "ops-alerts"}>
          {metrics.data.alerts.length ? <WarningCircle size={18} /> : <CheckCircle size={18} />}
          <div>
            <strong>{metrics.data.alerts.length ? "需要处理的告警" : "当前没有触发告警阈值"}</strong>
            {metrics.data.alerts.length > 0 && metrics.data.alerts.map(a => <p role="alert" key={a.code}>{metricLabels[a.code] || a.code}：{a.count}</p>)}
          </div>
        </div>
      </section>}
      {status.loading ? (
        <Loading />
      ) : (
        status.data && (
          <>
            <div className="ops-updated"><ClockCounterClockwise size={15} />统计时间：{new Date(status.data.generated_at).toLocaleString()}</div>
            <div className="ops-card-grid">
              <section className="ops-card"><div className="ops-card-title"><Pulse size={18} /><h2>处理任务</h2></div><Counts rows={status.data.jobs} jobs /></section>
              <section className="ops-card"><div className="ops-card-title"><ArrowsClockwise size={18} /><h2>物理清理</h2></div><Counts rows={status.data.cleanup} /></section>
              <section className="ops-card"><div className="ops-card-title"><CheckCircle size={18} /><h2>回答生成</h2></div><Counts rows={status.data.generation} /></section>
            </div>
            <p className="ops-note">运维角色仅查看汇总状态；内容、模型配置和访问凭证由对应管理员管理。</p>
          </>
        )
      )}
    </>
  );
}
