import DocumentUpload from "../../components/DocumentUpload";
import { ArrowClockwise, ArrowLeft, ShieldCheck } from "@phosphor-icons/react";
import { useEffect, useState } from "react";
import type { Row } from "../../api";
import {
  Badge,
  Empty,
  ErrorNote,
  Loading,
  stateNames,
  useData,
} from "../../ui";
import AclDialog from "../../components/AclDialog";
import DocumentMetadata from "../../components/DocumentMetadata";
import PublicationHistory from "./PublicationHistory";
import IndexMaintenance from "./IndexMaintenance";
import ChunkWorkspace from "./ChunkWorkspace";
import { knowledgeClient, knowledgePaths } from "./client";
import { request } from "../../api";

const flowSteps = [
  ["UPLOAD", "上传"],
  ["PARSED", "解析"],
  ["REVIEW", "审核切片"],
  ["INDEXED", "建立索引"],
  ["PUBLISHED", "发布生效"],
] as const;

function flowState(version: Row | undefined) {
  if (!version) return "UPLOAD";
  if (version.ever_published) return "PUBLISHED";
  if (version.state === "READY") return "INDEXED";
  if (version.state === "PARSED") return "REVIEW";
  return "PARSED";
}

function sleep(ms: number) {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

function DocumentFlow({
  doc,
  version,
  busy,
  action,
  afterPublish,
}: {
  doc: Row;
  version?: Row;
  busy: boolean;
  action: (fn: () => Promise<unknown>) => Promise<void>;
  afterPublish: () => void;
}) {
  const [oneClick, setOneClick] = useState(false);
  const state = flowState(version);
  const canOneClick = !!version?.configuration_id && ["PARSED", "FAILED"].includes(version.state);
  async function indexAndPublish() {
    if (!version || !canOneClick || oneClick) return;
    if (!window.confirm("将建立索引，等待处理完成后发布当前版本。是否继续？")) return;
    setOneClick(true);
    try {
      await action(async () => {
        await knowledgeClient.index(version.id);
        for (let attempt = 0; attempt < 90; attempt += 1) {
          await sleep(2000);
          const versions = await request<Row[]>(knowledgePaths.versions(doc.id));
          const latest = versions.find((item) => item.id === version.id);
          if (!latest) throw new Error("文档版本已不存在，请刷新后重试");
          if (latest.state === "FAILED") throw new Error("索引任务失败，请到任务中心查看错误详情");
          if (latest.state === "READY") {
            await knowledgeClient.publish(doc.id, {
              version_id: latest.id,
              revision: doc.revision,
              version_revision: latest.revision,
            });
            afterPublish();
            return;
          }
        }
        throw new Error("索引仍在处理中，请到任务中心查看进度");
      });
    } finally {
      setOneClick(false);
    }
  }
  return <section className="document-flow panel">
    <div className="document-flow-head">
      <div><h2>文档生效流程</h2><p>按顺序完成后，内容才会进入查询和问答。</p></div>
      <div className="flow-actions">
        {state === "REVIEW" && <button className="flow-secondary" onClick={() => document.getElementById("chunk-source-preview")?.scrollIntoView({ behavior: "smooth", block: "start" })}>审核切片</button>}
        {canOneClick && <button className="primary flow-action" disabled={busy || oneClick} onClick={() => void indexAndPublish()}>{oneClick ? "正在处理…" : "一键索引并发布"}</button>}
      </div>
    </div>
    <div className="document-flow-steps" aria-label="文档处理流程">
      {flowSteps.map(([key, label], index) => {
        const current = flowSteps.findIndex(([step]) => step === state);
        const done = index < current || state === "PUBLISHED";
        const active = index === current;
        return <div className={`document-flow-step${done ? " is-done" : ""}${active ? " is-active" : ""}`} key={key}>
          <span>{done ? "✓" : index + 1}</span><b>{label}</b>{index < flowSteps.length - 1 && <i />}
        </div>;
      })}
    </div>
    {!version?.configuration_id && state !== "PUBLISHED" && <p className="document-flow-hint">当前知识库尚未发布处理配置，请先在上方“处理与查询配置”中创建并发布配置。</p>}
    {version?.configuration_id && state === "REVIEW" && <p className="document-flow-hint">切片已生成。确认内容无误后，可以点击“一键索引并发布”。</p>}
  </section>;
}
export default function DocumentDetail({
  doc,
  back,
}: {
  doc: Row;
  back: () => void;
}) {
  const versions = useData<Row[]>(knowledgePaths.versions(doc.id), []),
    [active, setActive] = useState<Row | null>(null),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false),
    [acl, setAcl] = useState(false),
    [refreshEpoch, setRefreshEpoch] = useState(0);
  useEffect(() => {
    if (
      versions.data.length &&
      !versions.data.some((version) => version.id === active?.id)
    )
      setActive(versions.data[0]);
  }, [versions.data, active]);
  async function refresh() {
    await versions.reload();
    setRefreshEpoch((epoch) => epoch + 1);
  }
  async function action(run: () => Promise<unknown>) {
    setBusy(true);
    setError("");
    try {
      await run();
      await refresh();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  const current = versions.data.find((version) => version.id === active?.id);
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
          <button onClick={() => void refresh()}>
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
        {current && !current.configuration_id && current.state === "READY" && (
          <button
            disabled={busy}
            onClick={() => {
              if (
                window.confirm(
                  "将当前知识库配置绑定到此旧索引。只有Embedding地址、模型、修订和维度完全相同才会成功；原文和切片保持不变。",
                )
              )
                void action(async () => {
                  await knowledgeClient.bindConfiguration(
                    current.id,
                    current.revision,
                  );
                  setActive(null);
                });
            }}
          >
            绑定当前兼容配置
          </button>
        )}
        {current && (
          <button
            disabled={busy}
            onClick={() => {
              if (
                window.confirm(
                  "将按知识库当前发布配置创建新的处理版本。旧发布和人工修订保留在原版本；新解析不会自动合并修订。是否继续？",
                )
              )
                void action(async () => {
                  await knowledgeClient.reprocess(current.id);
                  setActive(null);
                });
            }}
          >
            按当前配置重新处理
          </button>
        )}
        {current && (
          <span className="muted">
            处理配置：
            {current.configuration_id
              ? current.configuration_id.slice(0, 8)
              : "历史部署配置"}
          </span>
        )}
        <details>
          <summary>上传新版与成本预估</summary>
          <DocumentUpload
            knowledgeBaseId={doc.kb_id}
            documentId={doc.id}
            onUploaded={async () => {
              setActive(null);
              await refresh();
            }}
          />
        </details>
      </div>
      <DocumentFlow
        doc={doc}
        version={current}
        busy={busy}
        action={action}
        afterPublish={back}
      />
      {versions.loading ? (
        <Loading />
      ) : !versions.error && !current ? (
        <Empty
          title="暂无可访问的内容版本"
          detail="请上传资料或检查当前版本权限。"
        />
      ) : (
        current && (
          <ChunkWorkspace
            key={current.id}
            version={current}
            doc={doc}
            busy={busy}
            action={action}
            afterPublish={back}
          />
        )
      )}
      {current && (
        <IndexMaintenance
          key={`${current.id}:${refreshEpoch}`}
          version={current}
          busy={busy}
          action={action}
        />
      )}
      <PublicationHistory
        key={`${doc.id}:${refreshEpoch}`}
        documentId={doc.id}
      />
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
          resource={{ id: doc.id }}
          close={() => setAcl(false)}
          onSaved={back}
        />
      )}
    </>
  );
}
