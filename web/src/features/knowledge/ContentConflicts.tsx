import { useState } from "react";
import type { Row } from "../../api";
import { post } from "../../api";
import { Dialog, ErrorNote, Loading, useData } from "../../ui";

export default function ContentConflicts({
  version,
  saved,
}: {
  version: Row;
  saved: () => Promise<void>;
}) {
  const [page, setPage] = useState(0),
    [selected, setSelected] = useState<Row | null>(null),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const data = useData<Row[]>(
    `/document-versions/${version.id}/content-conflicts?page=${page}`,
    [],
  );
  return (
    <section className="panel">
      <h3>新解析与旧人工修订</h3>
      <ErrorNote error={data.error} />
      {data.loading ? (
        <Loading />
      ) : data.error ? null : !data.data.length ? (
        <p className="muted">本页没有待对照的旧人工修订。</p>
      ) : (
        <>
          <p>
            新解析不会自动覆盖或合并人工内容。逐项核对后选择处理方式；未处理完不能索引或发布。
          </p>
          {data.data.map((row) => (
            <div key={row.id} className="section-title">
              <span>
                旧切片 {Number(row.previous.ordinal_no) + 1} ·{" "}
                {row.resolution ? "已处理" : "待核对"}
              </span>
              <button
                disabled={!!row.resolution || version.ever_published}
                onClick={() => {
                  setSelected(row);
                  setError("");
                }}
              >
                对照并处理
              </button>
            </div>
          ))}
          <div className="pagination">
            <button disabled={!page} onClick={() => setPage(page - 1)}>
              上一页冲突
            </button>
            <span>{page + 1}</span>
            <button
              disabled={data.data.length < 100}
              onClick={() => setPage(page + 1)}
            >
              下一页冲突
            </button>
          </div>
        </>
      )}
      {selected && (
        <Dialog title="对照人工修订" close={() => setSelected(null)}>
          <ErrorNote error={error} />
          <h3>旧原文提取</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>
            {selected.previous.source_text || "人工补充，无原文件定位"}
          </pre>
          <h3>已保存的人工内容</h3>
          <pre style={{ whiteSpace: "pre-wrap" }}>
            {selected.previous.content}
          </pre>
          <p>
            旧状态：{selected.previous.enabled ? "启用" : "禁用"} · 标签：
            {selected.previous.tags_json}
          </p>
          <h3>原文完全相同的新片段（最多20个）</h3>
          {selected.matching_chunks.length ? (
            selected.matching_chunks.map((row: Row) => (
              <pre key={row.id} style={{ whiteSpace: "pre-wrap" }}>
                切片 {Number(row.ordinal_no) + 1}：{row.content}
              </pre>
            ))
          ) : (
            <p>未找到完全相同的原文，请先在工作台核对新解析。</p>
          )}
          <form
            onSubmit={async (event) => {
              event.preventDefault();
              const form = new FormData(event.currentTarget),
                choice = String(form.get("choice"));
              setBusy(true);
              setError("");
              const target = selected.matching_chunks.find(
                (row: Row) => row.id === choice,
              );
              try {
                await post(
                  `/document-versions/${version.id}/content-conflicts/${selected.id}/resolution`,
                  {
                    revision: version.revision,
                    action: target ? "APPLY_TO_CHUNK" : choice,
                    target: target
                      ? { id: target.id, revision: target.revision }
                      : null,
                    reason: String(form.get("reason")),
                  },
                );
                await saved();
                await data.reload();
                setSelected(null);
              } catch (cause) {
                setError((cause as Error).message);
              } finally {
                setBusy(false);
              }
            }}
          >
            <label>
              处理方式
              <select name="choice">
                <option value="KEEP_NEW">
                  保留当前新内容（或已在工作台手动处理）
                </option>
                <option value="APPEND_OLD">
                  追加旧人工内容，标为无当前原文件定位
                </option>
                {selected.matching_chunks
                  .filter((row: Row) => !row.context_id)
                  .map((row: Row) => (
                    <option key={row.id} value={row.id}>
                      采用旧人工内容替换新切片 {Number(row.ordinal_no) + 1}
                    </option>
                  ))}
              </select>
            </label>
            <p className="muted">
              采用或追加时重新校验当前模型预算，保留旧启用状态与标签。FAQ/父子关系需通过对应组编辑器核对，不自动推断关系。
            </p>
            <label>
              原因
              <input name="reason" required maxLength={1000} />
            </label>
            <button disabled={busy}>保存处理结果</button>
          </form>
        </Dialog>
      )}
    </section>
  );
}
