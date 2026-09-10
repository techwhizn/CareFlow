import { useState } from "react";
import type { Row } from "../../api";
import { ErrorNote } from "../../ui";
const labels: Record<string, string> = {
  recall_at_10: "Recall@10",
  hit_at_10: "Hit@10",
  mrr_at_10: "MRR@10",
  recall_at_6: "Recall@6",
  hit_at_6: "Hit@6",
  mrr_at_6: "MRR@6",
  failure_rate: "请求失败率",
  answer_failure_rate: "回答生成失败率",
  search_p50_ms: "搜索P50毫秒",
  search_p95_ms: "搜索P95毫秒",
  answer_p50_ms: "回答P50毫秒",
  answer_p95_ms: "回答P95毫秒",
  unknown_cost_cases: "费用未完整确定题数",
};
function validate(input: unknown): Row {
  const row = input as Row;
  if (
    !row ||
    row.schema_version !== 2 ||
    typeof row.dataset_digest !== "string" ||
    typeof row.label !== "string" ||
    typeof row.baseline_status !== "string" ||
    !Array.isArray(row.results) ||
    row.results.length > 500 ||
    !row.summary
  )
    throw Error("需要版本2的评估报告");
  if (
    row.results.some(
      (r: Row) =>
        !r ||
        typeof r.case_id !== "string" ||
        typeof r.question !== "string" ||
        typeof r.reference_answer !== "string" ||
        [
          "error_code",
          "answer_error_code",
          "evidence_status",
          "configuration_id",
          "application_configuration_id",
        ].some((key) => r[key] != null && typeof r[key] !== "string") ||
        ["retrieval_top10_ids", "final_evidence_ids"].some(
          (key) =>
            r[key] != null &&
            (!Array.isArray(r[key]) ||
              r[key].length > 10 ||
              r[key].some((id: unknown) => typeof id !== "string")),
        ) ||
        ("answer" in r && typeof r.answer !== "string"),
    )
  )
    throw Error("报告题目格式不合法");
  return row;
}
function number(value: unknown) {
  return typeof value === "number" && Number.isFinite(value)
    ? value.toFixed(4)
    : "未测量";
}
export default function EvaluationReports() {
  const [first, setFirst] = useState<Row | null>(null),
    [second, setSecond] = useState<Row | null>(null),
    [error, setError] = useState(""),
    [reviews, setReviews] = useState<Record<string, Row>>({});
  const compatible =
    !!first &&
    !!second &&
    first.dataset_digest === second.dataset_digest &&
    first.answer_generation_enabled === second.answer_generation_enabled &&
    JSON.stringify(first.results.map((r: Row) => r.case_id).sort()) ===
      JSON.stringify(second.results.map((r: Row) => r.case_id).sort());
  async function load(file: File | undefined, side: number) {
    if (!file) return;
    setError("");
    try {
      if (file.size > 16 * 1024 * 1024) throw Error("报告最多16MiB");
      const value = validate(JSON.parse(await file.text()));
      if (side === 0) {
        setFirst(value);
        setReviews({});
      } else setSecond(value);
    } catch (e) {
      setError((e as Error).message);
    }
  }
  return (
    <details>
      <summary>评估报告、版本对比与回答评审</summary>
      <p>
        导入运行工具生成的本地报告。报告在浏览器中读取，不上传服务器；候选标注或缺失指标不作为已通过的质量结论。
      </p>
      <ErrorNote error={error} />
      <label>
        报告A
        <input
          type="file"
          accept=".json"
          onChange={(e) => {
            void load(e.target.files?.[0], 0);
            e.target.value = "";
          }}
        />
      </label>
      <label>
        报告B（可选对比）
        <input
          type="file"
          accept=".json"
          onChange={(e) => {
            void load(e.target.files?.[0], 1);
            e.target.value = "";
          }}
        />
      </label>
      {first && (
        <>
          <p>
            A：{first.label} ·{" "}
            {first.baseline_status === "HUMAN_REVIEWED_LABELS"
              ? "题目标注已评审"
              : "候选标注，尚未完成评审"}{" "}
            · {first.results.length}题
          </p>
          {second && !compatible && (
            <p role="alert">
              两份报告的题目标注、身份或执行阶段不同，不能计算可比差值。
            </p>
          )}
          <table>
            <thead>
              <tr>
                <th>指标</th>
                <th>A</th>
                {second && (
                  <>
                    <th>B</th>
                    <th>B−A</th>
                  </>
                )}
              </tr>
            </thead>
            <tbody>
              {Object.entries(labels).map(([key, label]) => (
                <tr key={key}>
                  <td>{label}</td>
                  <td>{number(first.summary[key])}</td>
                  {second && (
                    <>
                      <td>{number(second.summary[key])}</td>
                      <td>
                        {compatible &&
                        typeof first.summary[key] === "number" &&
                        typeof second.summary[key] === "number"
                          ? number(second.summary[key] - first.summary[key])
                          : "不可比较"}
                      </td>
                    </>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
          <p>
            已知客户费用（按币种分别汇总）：
            {JSON.stringify(first.summary.known_customer_costs_by_currency)}
            ；未知费用不计为零。重排Top10是在证据门槛与最终6条上限之前测量。
          </p>
          <h3>报告A逐题分析与人工评分</h3>
          <p>
            已在本页记录 {Object.keys(reviews).length}{" "}
            题评分。导出评分后用报告工具生成带评分的独立报告，原始报告保留不变。
          </p>
          {first.results.map((r: Row) => (
            <details key={r.case_id}>
              <summary>
                {r.case_id} · HTTP {r.status} · Top10命中{" "}
                {number(r.retrieval_top10?.hit)} · 最终命中{" "}
                {number(r.final_evidence?.hit)} · {r.question}
              </summary>
              <p>参考答案：{r.reference_answer}</p>
              <p>实际回答：{r.answer || "此运行没有完成的回答正文"}</p>
              <p>
                错误：{r.error_code || r.answer_error_code || "无"}；证据状态：
                {r.evidence_status || "未知"}
              </p>
              <p>
                查询配置：{r.configuration_id}；应用配置：
                {r.application_configuration_id || "无"}
              </p>
              <p>Top10：{(r.retrieval_top10_ids || []).join(", ")}</p>
              <p>最终证据：{(r.final_evidence_ids || []).join(", ")}</p>
              <p>排除原因：{JSON.stringify(r.excluded || [])}</p>
              {r.answer_completed && (
                <form
                  onSubmit={(e) => {
                    e.preventDefault();
                    const data = new FormData(e.currentTarget);
                    setReviews((previous) => ({
                      ...previous,
                      [r.case_id]: {
                        case_id: r.case_id,
                        reviewer: String(data.get("reviewer")),
                        human_reviewed: true,
                        correct: data.get("correct") === "true",
                        supported: data.get("supported") === "true",
                        citations_correct:
                          data.get("citations_correct") === "true",
                        refused: data.get("refused") === "true",
                        note: String(data.get("note")),
                        reviewed_at: new Date().toISOString(),
                      },
                    }));
                  }}
                >
                  <label>
                    评审人
                    <input name="reviewer" required maxLength={200} />
                  </label>
                  {[
                    ["correct", "回答正确"],
                    ["supported", "证据支持"],
                    ["citations_correct", "引用准确"],
                    ["refused", "回答是否拒答"],
                  ].map(([key, label]) => (
                    <label key={key}>
                      {label}
                      <select name={key} required defaultValue="">
                        <option value="" disabled>
                          请选择
                        </option>
                        <option value="true">是</option>
                        <option value="false">否</option>
                      </select>
                    </label>
                  ))}
                  <label>
                    说明
                    <textarea name="note" maxLength={2000} />
                  </label>
                  <label>
                    <input type="checkbox" required />
                    我已人工阅读此题回答与证据
                  </label>
                  <button>记录本题评分</button>
                  {reviews[r.case_id] && <span>已记录</span>}
                </form>
              )}
            </details>
          ))}
          <button
            disabled={!Object.keys(reviews).length}
            onClick={() => {
              const url = URL.createObjectURL(
                new Blob([JSON.stringify(Object.values(reviews), null, 2)], {
                  type: "application/json",
                }),
              );
              const a = document.createElement("a");
              a.href = url;
              a.download = "human-evaluation-reviews.json";
              a.click();
              setTimeout(() => URL.revokeObjectURL(url), 1000);
            }}
          >
            导出人工评分
          </button>
        </>
      )}
    </details>
  );
}
