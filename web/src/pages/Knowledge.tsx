import KnowledgeOverview from "../components/KnowledgeOverview";
import AclDialog from "../components/AclDialog";
import DocumentUpload from "../components/DocumentUpload";
import {
  ArrowClockwise,
  ArrowLeft,
  ArrowRight,
  Books,
  CaretRight,
  FileText,
  MagnifyingGlass,
  PencilSimple,
  Plus,
  ShieldCheck,
  Stack,
} from "@phosphor-icons/react";
import React, { useEffect, useState } from "react";
import type { Row } from "../api";
import { downloadSource, post, put, request } from "../api";
import {
  Badge,
  Dialog,
  Empty,
  ErrorNote,
  Loading,
  stateNames,
  useData,
} from "../ui";
export default function Knowledge() {
  const k = useData<Row[]>("/knowledge-bases", []);
  const [selected, setSelected] = useState<Row | null>(null),
    [creating, setCreating] = useState(false),
    [filter, setFilter] = useState(""),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  async function create(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const data = new FormData(e.currentTarget);
    setBusy(true);
    try {
      await post("/knowledge-bases", {
        name: data.get("name"),
        description: data.get("description"),
      });
      setCreating(false);
      await k.reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  if (selected)
    return (
      <KnowledgeDetail
        kb={selected}
        back={() => {
          setSelected(null);
          void k.reload();
        }}
      />
    );
  return (
    <>
      <div className="page-heading">
        <div>
          <h1>知识库</h1>
          <p>把资料组织成可授权、可发布、可复用的知识。</p>
        </div>
        <button className="primary" onClick={() => setCreating(true)}>
          <Plus />
          创建知识库
        </button>
      </div>
      <div className="toolbar">
        <div className="search-input">
          <MagnifyingGlass />
          <input
            aria-label="搜索知识库"
            placeholder="搜索知识库名称…"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
          />
        </div>
        <span className="muted">{k.data.length} 个知识库</span>
      </div>
      <ErrorNote error={k.error} />
      {k.loading ? (
        <Loading />
      ) : !k.data.length ? (
        <Empty
          title="还没有知识库"
          detail="创建知识库后，你可以上传资料、调整切片并发布。"
        />
      ) : (
        <div className="kb-grid">
          {k.data
            .filter((x) => x.name.includes(filter))
            .map((x) => (
              <button
                className="kb-card"
                key={x.id}
                onClick={() => setSelected(x)}
              >
                <div className="kb-icon">
                  <Books size={24} />
                </div>
                <Badge value={x.status} />
                <h3>{x.name}</h3>
                <p>{x.description || "尚未填写知识库说明"}</p>
                <footer>
                  配置版本 {x.revision}
                  <ArrowRight />
                </footer>
              </button>
            ))}
        </div>
      )}
      {creating && (
        <Dialog title="创建知识库" close={() => setCreating(false)}>
          <form onSubmit={create}>
            <ErrorNote error={error} />
            <label>
              名称
              <input
                name="name"
                required
                maxLength={200}
                placeholder="例如：产品与技术资料"
              />
            </label>
            <label>
              说明
              <textarea
                name="description"
                maxLength={2000}
                placeholder="描述资料范围与使用场景"
              />
            </label>
            <div className="notice">
              知识库只管理知识。业务意图与路由由应用层独立配置。
            </div>
            <button className="primary" disabled={busy}>
              创建知识库
            </button>
          </form>
        </Dialog>
      )}
    </>
  );
}

function KnowledgeDetail({ kb, back }: { kb: Row; back: () => void }) {
  const [page, setPage] = useState(0),
    [doc, setDoc] = useState<Row | null>(null),
    [acl, setAcl] = useState(false);
  const docs = useData<Row[]>(
    `/knowledge-bases/${kb.id}/documents?page=${page}`,
    [],
  );
  if (doc)
    return (
      <DocumentDetail
        doc={doc}
        back={() => {
          setDoc(null);
          void docs.reload();
        }}
      />
    );
  return (
    <>
      <button className="back" onClick={back}>
        <ArrowLeft />
        所有知识库
      </button>
      <div className="page-heading">
        <div>
          <div className="title-with-badge">
            <h1>{kb.name}</h1>
            <Badge value={kb.status} />
          </div>
          <p>{kb.description || "上传资料，逐步完善企业知识。"}</p>
        </div>
        <button onClick={() => setAcl(true)}>
          <ShieldCheck />
          授权管理
        </button>
      </div>
      <KnowledgeOverview id={kb.id} onSaved={back} />
      <div className="tabs">
        <button className="active">文档</button>
        <span>上传 → 解析 → 审核切片 → 索引 → 发布</span>
      </div>
      <ErrorNote error={docs.error} />
      <DocumentUpload knowledgeBaseId={kb.id} onUploaded={docs.reload} />
      <div className="section-title">
        <h2>文档列表</h2>
        <button onClick={() => void docs.reload()}>
          <ArrowClockwise />
          刷新
        </button>
      </div>
      {docs.loading ? (
        <Loading />
      ) : !docs.data.length ? (
        <Empty
          title="等待第一份资料"
          detail="文件上传后先完成解析，审核切片并索引后才能发布。"
          icon={FileText}
        />
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>文档名称</th>
                <th>发布状态</th>
                <th>修订</th>
                <th>创建时间</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {docs.data.map((d) => (
                <tr key={d.id}>
                  <td>
                    <FileText size={20} />
                    {d.title}
                  </td>
                  <td>
                    <Badge value={d.published_version ? "已发布" : "草稿"} />
                  </td>
                  <td>{d.revision}</td>
                  <td>{new Date(d.created_at).toLocaleDateString()}</td>
                  <td>
                    <button className="text-button" onClick={() => setDoc(d)}>
                      查看与编辑
                      <CaretRight />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <div className="pagination">
        <button disabled={!page} onClick={() => setPage(page - 1)}>
          上一页
        </button>
        <span>第 {page + 1} 页</span>
        <button
          disabled={docs.data.length < 50}
          onClick={() => setPage(page + 1)}
        >
          下一页
        </button>
      </div>
      {acl && (
        <AclDialog
          resource={{id:kb.id}}
          type="knowledge-bases"
          close={() => setAcl(false)}
          onSaved={back}
        />
      )}
    </>
  );
}


function DocumentDetail({ doc, back }: { doc: Row; back: () => void }) {
  const versions = useData<Row[]>(`/documents/${doc.id}/versions`, []),
    [active, setActive] = useState<Row | null>(null),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false),
    [acl, setAcl] = useState(false);
  useEffect(() => {
    if (versions.data.length && !active) setActive(versions.data[0]);
  }, [versions.data]);
  async function action(run: () => Promise<unknown>) {
    setBusy(true);
    setError("");
    try {
      await run();
      await versions.reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <button className="back" onClick={back}>
        <ArrowLeft />
        返回文档列表
      </button>
      <div className="page-heading">
        <div>
          <h1>{doc.title}</h1>
          <p>文档原文、知识切片与发布版本始终可追溯。</p>
        </div>
        <div className="button-row">
          <button onClick={() => setAcl(true)}>
            <ShieldCheck />
            文档权限
          </button>
          <button onClick={() => void versions.reload()}>
            <ArrowClockwise />
            刷新状态
          </button>
        </div>
      </div>
      <ErrorNote error={error || versions.error} />
      <div className="version-bar">
        <label>
          当前版本
          <select
            aria-label="当前文档版本"
            value={active?.id || ""}
            onChange={(e) =>
              setActive(
                versions.data.find((v) => v.id === e.target.value) || null,
              )
            }
          >
            {versions.data.map((v, i) => (
              <option key={v.id} value={v.id}>
                {v.id.slice(0, 8)} · {stateNames[v.state] || v.state} ·{" "}
                {i === 0 ? "最新版本" : "历史版本"}
              </option>
            ))}
          </select>
        </label>
        {active && (
          <>
            <Badge
              value={
                versions.data.find((v) => v.id === active.id)?.state ||
                active.state
              }
            />
            <span className="muted">
              {active.ever_published
                ? "已发布 · 内容不可变"
                : "草稿 · 不影响线上知识"}
            </span>
          </>
        )}
        <label className="file-button">
          上传新版
          <input
            type="file"
            disabled={busy}
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file)
                void action(async () => {
                  const data = new FormData();
                  data.append("file", file);
                  await request(`/documents/${doc.id}/versions`, {
                    method: "POST",
                    body: data,
                  });
                  setActive(null);
                });
            }}
          />
        </label>
      </div>
      {active && (
        <ChunkWorkspace
          key={active.id}
          version={versions.data.find((v) => v.id === active.id) || active}
          doc={doc}
          busy={busy}
          action={action}
          afterPublish={back}
        />
      )}
      <div className="danger-area">
        <div>
          <b>删除文档</b>
          <p>立即停止访问并隐藏依赖此文档的历史答案，后台清理另行执行。</p>
        </div>
        <button
          className="danger"
          onClick={() => {
            if (
              window.confirm(
                `确认删除“${doc.title}”？检索、引用和相关历史答案将不可访问。`,
              )
            )
              void action(async () => {
                await request(`/documents/${doc.id}?revision=${doc.revision}`, {
                  method: "DELETE",
                });
                back();
              });
          }}
        >
          删除文档
        </button>
      </div>
      {acl && (
        <AclDialog
          type="documents"
          resource={{id:doc.id}}
          close={() => setAcl(false)}
          onSaved={back}
        />
      )}
    </>
  );
}

function ChunkWorkspace({
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
      `/document-versions/${version.id}/chunks?page=${page}`,
      [],
    ),
    [selected, setSelected] = useState<Row | null>(null),
    [edit, setEdit] = useState(""),
    [reason, setReason] = useState(""),
    [editing, setEditing] = useState(false);
  useEffect(() => {
    setSelected(chunks.data[0] || null);
  }, [chunks.data]);
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
              void action(() => downloadSource(version.id, version.filename))
            }
          >
            下载原文
          </button>
          {version.ever_published ? (
            <button
              onClick={() =>
                void action(async () => {
                  await post(`/document-versions/${version.id}/draft`);
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
                  post(`/document-versions/${version.id}/index`),
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
                await post(`/documents/${doc.id}/publications`, {
                  version_id: version.id,
                  revision: doc.revision,
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
                    {JSON.stringify(JSON.parse(selected.location), null, 2)}
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
                await put(`/chunks/${selected.id}`, {
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
