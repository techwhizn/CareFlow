import type { Row } from "../../api";
import { ErrorNote, useData } from "../../ui";

export function CostView({ quote }: { quote: Row }) {
  return (
    <div>
      <p>
        {quote.basis === "ESTIMATE"
          ? "导入估算"
          : quote.basis === "PENDING"
            ? "执行中，尚未最终核算"
            : "实际用量核算"}{" "}
        · {quote.currency || "币种未配置"}
      </p>
      {(
        [
          ["customer", "客户计费"],
          ["provider", "内部资源成本"],
        ] as const
      ).map(
        ([key, title]) =>
          quote[key] && (
            <p key={key}>
              {title}：
              {quote[key].amount ??
                (quote[key].state === "UNCONFIGURED"
                  ? "未配置费率，无法核算"
                  : `总额未确定，已知小计 ${quote[key].known_subtotal}`)}
            </p>
          ),
      )}
      {quote.rule_id && <small>固定规则版本：{quote.rule_id}</small>}
    </div>
  );
}
export function ExecutionCost({ path }: { path: string }) {
  const cost = useData<Row | null>(path, null);
  return (
    <section className="cost-panel">
      <h3>成本核算</h3>
      <ErrorNote error={cost.error} />
      {cost.data && <CostView quote={cost.data} />}
      <button onClick={() => void cost.reload()}>刷新成本</button>
    </section>
  );
}
