import { useState } from "react";
import type { Row } from "../../api";
import { ErrorNote, Loading, useData } from "../../ui";

export default function ChunkHistory({ version }: { version: string }) {
  const [page, setPage] = useState(0);
  const data = useData<Row[]>(
    `/document-versions/${version}/chunk-changes?page=${page}`,
    [],
  );
  const names: Record<string, string> = {
    EDIT: "编辑",
    SPLIT: "拆分",
    MERGE: "合并",
    SET_ENABLED: "启停",
    TAGS: "标签",
    CONFLICT_RESOLVE: "冲突处理",
  };
  return (
    <section className="panel">
      <h3>切片修订记录</h3>
      <ErrorNote error={data.error} />
      {data.loading ? (
        <Loading />
      ) : data.error ? null : data.data.length ? (
        <>
          {data.data.map((row) => (
            <details key={row.id}>
              <summary>
                {names[row.action] ?? row.action} ·{" "}
                {new Date(row.created_at).toLocaleString()} · {row.reason}
              </summary>
              <p>操作者：{row.actor_id}</p>
              {(JSON.parse(row.previous_json) as Row[]).map(
                (previous, index) => (
                  <pre key={index} style={{ whiteSpace: "pre-wrap" }}>
                    {previous.content}
                  </pre>
                ),
              )}
            </details>
          ))}
          <div className="pagination">
            <button disabled={!page} onClick={() => setPage(page - 1)}>
              上一页修订
            </button>
            <span>{page + 1}</span>
            <button
              disabled={data.data.length < 50}
              onClick={() => setPage(page + 1)}
            >
              下一页修订
            </button>
          </div>
        </>
      ) : (
        <p className="muted">暂无此版本的切片操作记录。</p>
      )}
    </section>
  );
}
