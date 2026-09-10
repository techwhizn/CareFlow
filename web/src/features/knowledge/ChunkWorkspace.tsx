import { FileText, PencilSimple, Stack } from "@phosphor-icons/react";
import { useEffect, useState } from "react";
import type { Row } from "../../api";
import { Dialog, Empty, ErrorNote, Loading, useData } from "../../ui";
import { knowledgeClient, knowledgePaths } from "./client";

function sourceLocation(raw: unknown): Record<string, unknown> {
  try {
    const location: unknown = JSON.parse(String(raw));
    if (location && typeof location === "object" && !Array.isArray(location)) {
      return location as Record<string, unknown>;
    }
  } catch {
    // Invalid location must remain visibly unavailable.
  }
  return { warning: "来源位置格式异常，请联系知识库管理员核对。" };
}

export default function ChunkWorkspace({
  version,
  doc,
  busy,
  action,
  afterPublish,
}: {
  version: Row;
  doc: Row;
  busy: boolean;
  action: (fn: () => Promise<unknown>) => Promise<void>;
  afterPublish: () => void;
}) {
  const [page, setPage] = useState(0),
    chunks = useData<Row[]>(
      knowledgePaths.chunks(version.id, page),
      [],
    ),
    [selected, setSelected] = useState<Row | null>(null),
    [edit, setEdit] = useState(""),
    [reason, setReason] = useState(""),
    [editing, setEditing] = useState(false);
  useEffect(() => {
    setSelected(chunks.data[0] || null);
  }, [chunks.data]);
  const location = selected ? sourceLocation(selected.location) : {};
  return (
    <>
      <div className="section-title">
        <div>
          <h2>切片工作台</h2>
          <p className="muted">核对原文、修订知识，再发布给应用使用。</p>
        </div>
        <div className="button-row">
          <button
            onClick={() =>
              void action(() => knowledgeClient.download(version.id, version.filename))
            }
          >
            下载原文
          </button>
          {version.ever_published ? (
            <button
              onClick={() =>
                void action(async () => {
                  await knowledgeClient.draft(version.id);
                })
              }
            >
              复制为草稿
            </button>
          ) : (
            <button
              disabled={busy || !["PARSED", "FAILED"].includes(version.state)}
              onClick={() =>
                void action(() =>
                  knowledgeClient.index(version.id),
                )
              }
            >
              建立索引
            </button>
          )}
          <button
            className="primary"
            disabled={busy || version.state !== "READY"}
            onClick={() =>
              void action(async () => {
                await knowledgeClient.publish(doc.id, {
                  version_id: version.id,
                  revision: doc.revision,
                  version_revision: version.revision,
                });
                afterPublish();
              })
            }
          >
            {version.ever_published ? "回滚至此版本" : "发布此版本"}
          </button>
        </div>
      </div>
      <ErrorNote error={chunks.error} />
      {typeof location.warning === "string" && location.warning && (
        <div className="notice" role="note">
          解析质量提示：{location.warning}
        </div>
      )}
      {chunks.loading ? (
        <Loading />
      ) : !chunks.data.length ? (
        <div className="panel">
          <Empty
            icon={Stack}
            title="尚无可预览的切片"
            detail="解析任务完成后在此查看。请到任务中心检查当前阶段或失败原因。"
          />
        </div>
      ) : (
        <div className="chunk-workspace">
          <div className="source-pane">
            <header>
              <FileText />
              来源原文<span>选中切片对应内容</span>
            </header>
            {selected && (
              <>
                <pre>{selected.source_text}</pre>
                <div className="source-location">
                  <b>来源定位</b>
                  <pre>
                    {JSON.stringify(location, null, 2)}
                  </pre>
                </div>
              </>
            )}
          </div>
          <div className="chunk-pane">
            <header>
              <Stack />
              知识切片<span>本页 {chunks.data.length} 个</span>
            </header>
            <div className="chunk-list">
              {chunks.data.map((c, i) => (
                <button
                  key={c.id}
                  className={`chunk-card ${selected?.id === c.id ? "selected" : ""}`}
                  onClick={() => {
                    setSelected(c);
                    setEditing(false);
                  }}
                >
                  <div>
                    <b>切片 {page * 100 + i + 1}</b>
                    <span>
                      {c.token_count} Token{!c.enabled ? " · 已禁用" : ""}
                    </span>
                  </div>
                  <p>{c.content}</p>
                  <small>修订 {c.revision}</small>
                </button>
              ))}
            </div>
            {selected && !version.ever_published && (
              <div className="chunk-actions">
                <button
                  onClick={() => {
                    setEdit(selected.content);
                    setReason("");
                    setEditing(true);
                  }}
                >
                  <PencilSimple />
                  编辑选中切片
                </button>
              </div>
            )}
          </div>
        </div>
      )}
      <div className="pagination">
        <button disabled={!page} onClick={() => setPage(page - 1)}>
          上一页
        </button>
        <span>第 {page + 1} 页</span>
        <button
          disabled={chunks.data.length < 100}
          onClick={() => setPage(page + 1)}
        >
          下一页
        </button>
      </div>
      {editing && selected && (
        <Dialog title="修订知识切片" close={() => setEditing(false)}>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              void action(async () => {
                await knowledgeClient.editChunk(selected.id, {
                  content: edit,
                  enabled: selected.enabled,
                  revision: selected.revision,
                  reason,
                });
                setEditing(false);
                await chunks.reload();
              });
            }}
          >
            <label>
              检索内容
              <textarea
                rows={10}
                value={edit}
                onChange={(e) => setEdit(e.target.value)}
                required
                maxLength={2000}
              />
            </label>
            <label>
              修改原因
              <input
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                required
              />
            </label>
            <p className="muted">
              修改保留原文，并使当前索引失效。重新索引后才能发布。
            </p>
            <button className="primary" disabled={busy}>
              保存修订
            </button>
          </form>
        </Dialog>
      )}
    </>
  );
}
