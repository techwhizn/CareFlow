import { ErrorNote, useData } from "../../ui";

type Stage = {
  stage: string;
  calls: number;
  known_tokens: number;
  unknown_usage_calls: number;
  not_called: number;
};
type Summary = {
  ocr?: {
    attempted_pages: number;
    completed_pages: number;
    failed_pages: number;
    uncertain_pages: number;
  };
  storage?: {
    known_source_bytes: number;
    source_objects: number;
    unknown_size_objects: number;
  };
  retrieval: Stage[];
  generation: {
    requests: number;
    known_tokens: number;
    unknown_usage_calls: number;
  };
  indexing?: {
    calls: number;
    known_tokens: number;
    unknown_usage_calls: number;
  };
};

export default function ModelUsagePanel({
  applicationId,
}: {
  applicationId?: string;
}) {
  const result = useData<Summary | null>(
    applicationId ? `/applications/${applicationId}/usage` : "/usage/models",
    null,
  );
  return (
    <section>
      <h3>模型实际用量</h3>
      <p>
        累计已知 Token
        与未回报调用分开统计。仅包含各项开始计量后的记录，更早的调用未补算。
      </p>
      <ErrorNote error={result.error} />
      {result.data && (
        <>
          <p>
            生成：{result.data.generation.known_tokens} 已知 Token ·{" "}
            {result.data.generation.requests} 个请求 ·{" "}
            {result.data.generation.unknown_usage_calls} 次用量未知或未回报
          </p>
          {result.data.retrieval.map((s) => (
            <p key={s.stage}>
              {s.stage === "EMBEDDING" ? "检索向量" : "重排"}：{s.known_tokens}{" "}
              已知 Token · {s.calls - s.not_called} 次调用 ·{" "}
              {s.unknown_usage_calls} 次用量未知或未回报
            </p>
          ))}
          {result.data.indexing && (
            <p>
              索引向量：{result.data.indexing.known_tokens} 已知 Token ·{" "}
              {result.data.indexing.calls} 次调用 ·{" "}
              {result.data.indexing.unknown_usage_calls} 次用量未知或未回报
            </p>
          )}
        </>
      )}
      {result.data?.ocr && (
        <p>
          OCR：{result.data.ocr.completed_pages} 页完成 ·{" "}
          {result.data.ocr.failed_pages} 页失败 ·{" "}
          {result.data.ocr.uncertain_pages}{" "}
          页处理中或结果未知（每次重试单独记录，自启用逐页计量起）
        </p>
      )}
      {result.data?.storage && (
        <p>
          知识源文件：{result.data.storage.known_source_bytes} 已知字节 ·{" "}
          {result.data.storage.source_objects} 个对象 ·{" "}
          {result.data.storage.unknown_size_objects}{" "}
          个对象大小未知。不含临时上传、索引和备份占用。
        </p>
      )}
      {applicationId && (
        <p>共享知识库的索引和存储资源归属企业，不重复分摊到每个应用。</p>
      )}
      <button onClick={() => void result.reload()}>刷新模型用量</button>
    </section>
  );
}
