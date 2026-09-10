import { ExecutionCost } from "../billing/CostView";
import { useState } from "react";
import type { Row } from "../../api";
import { ErrorNote, useData } from "../../ui";

const outcome: Record<string, string> = {
  RUNNING: "处理中",
  RECOVERED: "超时回收",
  SUCCEEDED: "成功",
  FAILED: "失败",
  CANCELLED: "取消",
  UNKNOWN: "早期记录，状态未采集",
};
function Details({ path }: { path: string }) {
  const data = useData<Row | null>(path, null);
  return (
    <div>
      <ErrorNote error={data.error} />
      <ExecutionCost path={`${path}/cost`} />
      {data.data && (
        <>
          <p>HTTP 请求 ID：{data.data.http_request_id || "早期未记录"}</p>
          <p>
            配置版本：
            {data.data.application_configuration_id ||
              data.data.configuration_id ||
              "早期未记录"}{" "}
            · 应用修订：{data.data.application_revision ?? "未知"}
          </p>
          <p>
            错误码：{data.data.error_code || "无"} · 结束时间：
            {data.data.completed_at
              ? new Date(data.data.completed_at).toLocaleString()
              : "未记录"}
          </p>
          {(data.data.retrieval_calls as Row[]).map((c) => (
            <p key={c.id}>
              {c.stage === "EMBEDDING" ? "查询向量" : "重排"} · {c.input_count}{" "}
              条输入 · {c.total_tokens ?? "未知"} Token ·{" "}
              {c.usage_state === "REPORTED"
                ? "已回报"
                : c.usage_state === "NOT_CALLED"
                  ? "未调用"
                  : "未回报或结果未知"}
            </p>
          ))}
          {(data.data.generation as Row[]).map((g, i) => (
            <p key={i}>
              生成：输入 {g.input_tokens ?? "未知"} · 输出{" "}
              {g.output_tokens ?? "未知"} · 总计 {g.total_tokens ?? "未知"}{" "}
              Token
            </p>
          ))}
        </>
      )}
    </div>
  );
}
export default function RequestLogsPanel({
  applicationId,
}: {
  applicationId?: string;
}) {
  const base = applicationId
    ? `/applications/${applicationId}/requests`
    : "/usage/requests";
  const [before, setBefore] = useState("");
  const [selected, setSelected] = useState("");
  const records = useData<{ items: Row[]; next_cursor: string }>(
    base + (before ? `?before=${before}` : ""),
    { items: [], next_cursor: "" },
  );
  return (
    <details>
      <summary>调用日志与用量明细</summary>
      <p>
        日志展示调用状态和模型用量，不包含问题、答案或密钥。可用搜索返回的请求
        ID 或问答开始事件的请求 ID 对照排查。
      </p>
      <ErrorNote error={records.error} />
      <form
        onSubmit={(e) => {
          e.preventDefault();
          const value = String(
            new FormData(e.currentTarget).get("request") || "",
          );
          setSelected(value);
        }}
      >
        <label>
          按请求 ID 查看
          <input
            name="request"
            required
            pattern="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
            placeholder="请求 UUID"
          />
        </label>
        <button>查询请求</button>
      </form>
      <button
        onClick={() => {
          setBefore("");
          setSelected("");
          void records.reload();
        }}
      >
        最新记录
      </button>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>请求 ID</th>
              <th>类型</th>
              <th>结果</th>
              <th>开始时间</th>
              <th>明细</th>
            </tr>
          </thead>
          <tbody>
            {records.data.items.map((r) => (
              <tr key={r.request_id}>
                <td>{r.request_id}</td>
                <td>
                  {r.operation === "ANSWER"
                    ? "问答"
                    : r.operation === "SEARCH"
                      ? "搜索"
                      : "早期未记录"}
                </td>
                <td>{outcome[r.outcome] || "未知"}</td>
                <td>{new Date(r.created_at).toLocaleString()}</td>
                <td>
                  <button onClick={() => setSelected(r.request_id)}>
                    查看用量
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {records.data.next_cursor && (
        <button
          onClick={() => {
            setBefore(records.data.next_cursor);
            setSelected("");
          }}
        >
          更早记录
        </button>
      )}
      {selected && <Details key={selected} path={`${base}/${selected}`} />}
    </details>
  );
}
