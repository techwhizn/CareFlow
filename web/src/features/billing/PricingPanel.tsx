import { useState } from "react";
import type { Row } from "../../api";
import { post, put } from "../../api";
import { ErrorNote, useData } from "../../ui";

const customer = {
  QUERY: "每次成功查询",
  PROCESSING_TASK: "每个开始处理的任务（重试不重复计）",
};
const provider = {
  SOURCE_WRITE_BYTE: "每字节新源文件写入",
  OCR_PAGE: "每页完成的 OCR",
  EMBEDDING_TOKEN: "每个 Embedding Token",
  RERANK_TOKEN: "每个 Rerank Token",
  GENERATION_INPUT_TOKEN: "每个生成输入 Token",
  GENERATION_OUTPUT_TOKEN: "每个生成输出 Token",
};
export default function PricingPanel() {
  const data = useData<Row | null>("/billing/rules", null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  async function activate(id: string | null) {
    setBusy(true);
    setError("");
    try {
      await put("/billing/active-rule", {
        rule_id: id,
        revision: data.data?.revision,
      });
      await data.reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <details>
      <summary>计费规则版本</summary>
      <p>
        客户按成功查询和已开始任务计费；内部资源成本按实际回报核算。单价均以一个单位为基准，留空表示未配置，填写
        0 表示明确免费。源文件写入成本不是按月存储订阅。
      </p>
      <ErrorNote error={error || data.error} />
      <p>
        当前规则：
        {data.data?.rules.find((r: Row) => r.id === data.data?.billing_rule_id)
          ?.name || "未配置"}
      </p>
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          const f = new FormData(e.currentTarget);
          setBusy(true);
          setError("");
          const rates = (side: string, keys: Record<string, string>) =>
            Object.fromEntries(
              Object.keys(keys).flatMap((k) => {
                const value = String(f.get(side + "." + k) || "").trim();
                return value ? [[k, value]] : [];
              }),
            );
          try {
            await post("/billing/rules", {
              name: f.get("name"),
              currency: f.get("currency"),
              customer_rates: rates("customer", customer),
              provider_rates: rates("provider", provider),
            });
            await data.reload();
          } catch (e) {
            setError((e as Error).message);
          } finally {
            setBusy(false);
          }
        }}
      >
        <label>
          规则名称
          <input name="name" required maxLength={200} />
        </label>
        <label>
          币种
          <select name="currency">
            <option value="CNY">CNY</option>
            <option value="USD">USD</option>
          </select>
        </label>
        {(
          [
            ["customer", "客户单价", customer],
            ["provider", "内部成本单价", provider],
          ] as const
        ).map(([side, title, keys]) => (
          <fieldset key={side}>
            <legend>{title}</legend>
            {Object.entries(keys).map(([key, label]) => (
              <label key={key}>
                {label}
                <input
                  type="number"
                  min="0"
                  max="1000000"
                  step="any"
                  name={side + "." + key}
                  placeholder="未配置"
                />
              </label>
            ))}
          </fieldset>
        ))}
        <button disabled={busy}>保存新规则版本</button>
      </form>
      <p>保存后需启用才影响新请求与任务；历史记录保留原规则。</p>
      {data.data?.rules.map((rule: Row) => (
        <div key={rule.id}>
          <p>
            {rule.name} · {rule.currency}{" "}
            <button
              disabled={busy || data.data?.billing_rule_id === rule.id}
              onClick={() => void activate(rule.id)}
            >
              {data.data?.billing_rule_id === rule.id ? "使用中" : "启用此版本"}
            </button>
          </p>
          <details>
            <summary>查看单价</summary>
            {Object.entries({ ...customer, ...provider }).map(
              ([key, label]) => (
                <p key={key}>
                  {label}：
                  {rule.customer_rates[key] ??
                    rule.provider_rates[key] ??
                    "未配置"}{" "}
                  {rule.currency}
                </p>
              ),
            )}
          </details>
        </div>
      ))}
      <button
        disabled={busy || !data.data?.billing_rule_id}
        onClick={() => void activate(null)}
      >
        停用规则（新调用金额显示未知）
      </button>
    </details>
  );
}
