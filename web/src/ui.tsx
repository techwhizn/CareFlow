import type { Icon } from "@phosphor-icons/react";
import { Books, CircleNotch, WarningCircle } from "@phosphor-icons/react";
import React, { useEffect, useRef, useState } from "react";
import type { Row } from "./api";
import { request } from "./api";
import { createLoader } from "./dataLoader";
export const stateNames: Row = {
  ACTIVE: "使用中",
  ARCHIVED: "已归档",
  QUEUED: "排队中",
  RUNNING: "处理中",
  PARSING: "解析中",
  PARSED: "待索引",
  INDEXING: "索引中",
  READY: "处理就绪",
  FAILED: "失败",
  DONE: "完成",
  CANCELLED: "已取消",
};
const labels: Row = {
  name: "姓名",
  role: "角色",
  id: "标识",
  active: "启用状态",
  kind: "凭证类型",
  subject_id: "主体 ID",
  expires_at: "到期时间",
  action: "操作",
  actor_id: "操作者",
  resource_id: "资源",
  created_at: "时间",
  resource_type: "资源类型",
  amount: "数量",
  state: "状态",
};
export function Badge({ value }: { value: string }) {
  return (
    <span
      className={`badge ${["FAILED", "CANCELLED"].includes(value) ? "bad" : ["READY", "DONE", "ACTIVE"].includes(value) ? "good" : "neutral"}`}
    >
      <span />
      {stateNames[value] || value}
    </span>
  );
}

export function Empty({
  title = "暂无内容",
  detail = "创建第一条记录，开始构建你的知识工作空间。",
  icon: Glyph = Books,
}: {
  title?: string;
  detail?: string;
  icon?: Icon;
}) {
  return (
    <div className="empty">
      <Glyph size={36} weight="duotone" />
      <h3>{title}</h3>
      <p>{detail}</p>
    </div>
  );
}

export function ErrorNote({ error }: { error: string }) {
  return error ? (
    <div className="error" role="alert">
      <WarningCircle size={18} />
      {error}
    </div>
  ) : null;
}

export function Loading() {
  return (
    <div className="loading">
      <CircleNotch className="spin" size={22} />
      正在获取数据…
    </div>
  );
}

export function Dialog({
  title,
  children,
  close,
}: {
  title: string;
  children: React.ReactNode;
  close: () => void;
}) {
  return (
    <div
      className="backdrop"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) close();
      }}
    >
      <section
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className="dialog"
      >
        <header>
          <h2>{title}</h2>
          <button className="text-button" onClick={close}>
            关闭
          </button>
        </header>
        {children}
      </section>
    </div>
  );
}

export function useData<T>(path: string, initial: T) {
  const empty = useRef(initial);
  const [snapshot, setSnapshot] = useState({ path, data: initial, loading: true, error: "" });
  const loader = useRef<ReturnType<typeof createLoader<T>> | null>(null);
  if (!loader.current) loader.current = createLoader<T>((url, signal) => request<T>(url, { signal }), setSnapshot, empty.current);
  const reload = () => loader.current!.load(path);
  useEffect(() => {
    void reload();
    return () => loader.current!.cancel();
  }, [path]);
  const current = snapshot.path === path ? snapshot : { data: empty.current, loading: true, error: "" };
  return { ...current, reload };
}

export function DataTable({
  rows,
  fields,
  action,
}: {
  rows: Row[];
  fields: string[];
  action?: (row: Row) => React.ReactNode;
}) {
  if (!rows.length)
    return (
      <Empty title="暂无记录" detail="相关操作发生后，记录会显示在这里。" />
    );
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            {fields.map((f) => (
              <th key={f}>{labels[f] || f}</th>
            ))}
            {action && <th>操作</th>}
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={r.id || i}>
              {fields.map((f) => (
                <td key={f}>
                  {r[f] === null
                    ? "—"
                    : typeof r[f] === "boolean"
                      ? r[f]
                        ? "是"
                        : "否"
                      : String(r[f])}
                </td>
              ))}
              {action && <td>{action(r)}</td>}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
