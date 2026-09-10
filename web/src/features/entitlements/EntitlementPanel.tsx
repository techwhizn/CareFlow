import { useState } from "react";
import { put } from "../../api";
import { ErrorNote, Loading, useData } from "../../ui";

const limits = [
  ["member_limit", "启用成员上限", 0, 100000],
  ["knowledge_base_limit", "知识库上限（含归档）", 0, 100000],
  ["storage_limit_bytes", "文件空间上限（字节）", 0, 1000000000000000],
  ["processing_limit", "累计处理任务上限", 0, 1000000000],
  ["query_limit", "累计查询上限", 0, 1000000000000],
  ["query_concurrency_limit", "同时查询上限", 1, 1000],
  ["task_concurrency_limit", "同时处理任务上限", 1, 100],
  ["pdf_page_limit", "单个 PDF 页数上限", 1, 500],
  ["warning_percent", "预警阈值（%）", 1, 100],
] as const;
type Limit = typeof limits[number][0];
type Configuration = Record<Limit, number> & {
  active: boolean; starts_at: string | null; expires_at: string | null; revision: number;
};
type Snapshot = {
  configuration: Configuration; available: boolean; unknown_source_objects: number;
  resources: Record<string, { used: number; limit: number; warning: boolean }>;
};
const names: Record<string, string> = {
  members: "成员", knowledge_bases: "知识库", storage_bytes: "文件空间（字节）",
  processing_tasks: "处理任务", queries: "查询（含预占）",
};
export default function EntitlementPanel({ changed }: { changed: () => Promise<void> }) {
  const data = useData<Snapshot | null>("/entitlement", null);
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);
  if (data.loading) return <Loading />;
  if (!data.data) return <ErrorNote error={data.error} />;
  const snapshot = data.data, config = snapshot.configuration;
  return <section>
    <h2>套餐与资源限制</h2>
    <ErrorNote error={error || data.error} />
    <p>当前{snapshot.available ? "可用" : "未生效、已到期或停用"}。变更保留累计用量；续期不会自动清零。处理次数按首次领取任务计算，内部重试不重复扣减。</p>
    <ul>{Object.entries(snapshot.resources).map(([key, resource]) => <li key={key}>
      {names[key] || key}：{resource.used.toLocaleString()} / {resource.limit.toLocaleString()}
      {resource.warning && <strong> · 已达到预警阈值</strong>}
    </li>)}</ul>
    {snapshot.unknown_source_objects > 0 && <p>有 {snapshot.unknown_source_objects} 个旧文件缺少大小记录，每个按 50 MiB 预留空间。</p>}
    <form key={config.revision} onSubmit={async event => {
      event.preventDefault();
      const fields = new FormData(event.currentTarget);
      setSaving(true); setError("");
      try {
        const values = Object.fromEntries(limits.map(([key]) => [key, Number(fields.get(key))]));
        await put("/entitlement", { ...values, revision: config.revision,
          active: fields.get("active") === "on", reason: fields.get("reason"),
          starts_at: fields.get("starts_at") || null, expires_at: fields.get("expires_at") || null });
        await data.reload(); await changed();
      } catch (failure) { setError((failure as Error).message); }
      finally { setSaving(false); }
    }}>
      {limits.map(([key, label, min, max]) => <label key={key}>{label}
        <input name={key} type="number" min={min} max={max} step="1" defaultValue={config[key]} required />
      </label>)}
      <label><input type="checkbox" name="active" defaultChecked={config.active} />启用套餐</label>
      <label>开始时间（带时区，留空立即生效）<input name="starts_at" defaultValue={config.starts_at || ""} placeholder="2026-09-10T00:00:00+08:00" /></label>
      <label>到期时间（带时区，留空不限）<input name="expires_at" defaultValue={config.expires_at || ""} placeholder="2027-09-10T00:00:00+08:00" /></label>
      <label>调整原因<input name="reason" maxLength={1000} required /></label>
      <p>停用或到期会阻止新增资源、查询和任务领取；已运行任务可完成，管理与已有文件读取保留。</p>
      <button className="primary" disabled={saving}>{saving ? "保存中…" : "保存套餐"}</button>
    </form>
  </section>;
}
