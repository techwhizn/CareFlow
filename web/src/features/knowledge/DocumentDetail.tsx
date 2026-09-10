import { ArrowClockwise, ArrowLeft, ShieldCheck } from "@phosphor-icons/react";
import { useEffect, useState } from "react";
import type { Row } from "../../api";
import { Badge, Empty, ErrorNote, Loading, stateNames, useData } from "../../ui";
import AclDialog from "../../components/AclDialog";
import DocumentMetadata from "../../components/DocumentMetadata";
import PublicationHistory from "./PublicationHistory";
import ChunkWorkspace from "./ChunkWorkspace";
import { knowledgeClient, knowledgePaths } from "./client";
export default function DocumentDetail({ doc, back }: { doc: Row; back: () => void }) {
  const versions = useData<Row[]>(knowledgePaths.versions(doc.id), []),
    [active, setActive] = useState<Row | null>(null),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false),
    [acl, setAcl] = useState(false);
  useEffect(() => {
    if (versions.data.length && !versions.data.some(version=>version.id===active?.id)) setActive(versions.data[0]);
  }, [versions.data, active]);
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
  const current = versions.data.find(version => version.id === active?.id);
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
      <DocumentMetadata id={doc.id} onSaved={back} />
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
        {current && (
          <>
            <Badge value={current.state} />
            <span className="muted">
              {current.ever_published
                ? "已发布 · 内容不可变"
                : "草稿 · 不影响线上知识"}
            </span>
          </>
        )}
        {current && !current.configuration_id && current.state === "READY" && <button disabled={busy} onClick={() => {
          if (window.confirm("将当前知识库配置绑定到此旧索引。只有Embedding地址、模型、修订和维度完全相同才会成功；原文和切片保持不变。")) void action(async () => {
            await knowledgeClient.bindConfiguration(current.id, current.revision); setActive(null);
          });
        }}>绑定当前兼容配置</button>}
        {current && <button disabled={busy} onClick={() => {
          if (window.confirm("将按知识库当前发布配置创建新的处理版本。旧发布和人工修订保留在原版本；新解析不会自动合并修订。是否继续？")) void action(async () => {
            await knowledgeClient.reprocess(current.id); setActive(null);
          });
        }}>按当前配置重新处理</button>}
        {current && <span className="muted">处理配置：{current.configuration_id ? current.configuration_id.slice(0, 8) : "历史部署配置"}</span>}
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
                  await knowledgeClient.uploadVersion(doc.id, data);
                  setActive(null);
                });
            }}
          />
        </label>
      </div>
      {versions.loading ? <Loading/> : !versions.error && !current ? <Empty title="暂无可访问的内容版本" detail="请上传资料或检查当前版本权限。"/> : current && (
        <ChunkWorkspace
          key={current.id}
          version={current}
          doc={doc}
          busy={busy}
          action={action}
          afterPublish={back}
        />
      )}
      <PublicationHistory documentId={doc.id} />
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
                await knowledgeClient.removeDocument(doc.id, doc.revision);
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
