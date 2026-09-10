type Hit = { id: string; score?: number | null };
type Usage = { state: string; total_tokens?: number | null };
type Trace = {
  trace_id: string;
  configuration_id?: string;
  publication_versions: string[];
  query_processing?: {
    original: string;
    rewritten: string;
    method: string;
    identifiers: string[];
  };
  timings_ms?: Record<string, number>;
  recall?: Record<string, Hit[]>;
  rerank?: { results: Hit[] };
  excluded?: { id: string; reason: string }[];
  model_usage?: {
    embedding: { configuration_id: string; usage: Usage }[];
    rerank: Usage;
  };
};
const stages: Record<string, string> = {
  recall: "召回",
  authorization: "权限复核",
  rerank: "重排",
  evidence: "证据整理与复核",
  total: "检索总耗时",
};
function usageLabel(usage: Usage) {
  if (usage.state === "REPORTED")
    return `${usage.total_tokens} Token（模型报告）`;
  if (usage.state === "NOT_CALLED") return "未调用";
  if (usage.state === "NOT_REPORTED") return "模型未提供用量";
  return "用量未知";
}
export default function RetrievalTrace({ result }: { result: Trace }) {
  if (!result.query_processing) return <p>当前身份无权查看调试详情。</p>;
  return (
    <section className="card">
      <h3>查询追踪</h3>
      <p>原问题：{result.query_processing.original}</p>
      <p>检索文本：{result.query_processing.rewritten}</p>
      <p>
        处理方式：空白规范化 · 型号/编号：
        {result.query_processing.identifiers.join("、") || "未提取"}
      </p>
      <p>
        追踪：<code>{result.trace_id}</code> · 查询配置：
        <code>{result.configuration_id || "未绑定"}</code>
      </p>
      <details>
        <summary>实际授权版本（{result.publication_versions.length}）</summary>
        <ul>
          {result.publication_versions.map((id) => (
            <li key={id}>
              <code>{id}</code>
            </li>
          ))}
        </ul>
      </details>
      <p>
        {Object.entries(result.timings_ms || {})
          .map(([key, value]) => `${stages[key] || key} ${value.toFixed(1)} ms`)
          .join(" · ")}
      </p>
      {result.model_usage && (
        <>
          <h4>本次模型报告用量</h4>
          <ul>
            {result.model_usage.embedding.map((entry, index) => (
              <li key={index}>
                Embedding · 配置 {entry.configuration_id || "历史配置"}：
                {usageLabel(entry.usage)}
              </li>
            ))}
            <li>Rerank：{usageLabel(result.model_usage.rerank)}</li>
          </ul>
        </>
      )}
      {Object.entries({
        ...(result.recall || {}),
        rerank: result.rerank?.results || [],
      }).map(([lane, hits]) => (
        <details key={lane}>
          <summary>
            {lane} 候选（{hits.length}）
          </summary>
          <table>
            <thead>
              <tr>
                <th>排名</th>
                <th>片段</th>
                <th>分数</th>
              </tr>
            </thead>
            <tbody>
              {hits.map((hit, index) => (
                <tr key={hit.id}>
                  <td>{index + 1}</td>
                  <td>
                    <code>{hit.id}</code>
                  </td>
                  <td>{hit.score ?? "无评分"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </details>
      ))}
      {!!result.excluded?.length && (
        <details>
          <summary>排除原因（{result.excluded.length}）</summary>
          <ul>
            {result.excluded.map((row) => (
              <li key={row.id}>
                <code>{row.id}</code>：{row.reason}
              </li>
            ))}
          </ul>
        </details>
      )}
    </section>
  );
}
