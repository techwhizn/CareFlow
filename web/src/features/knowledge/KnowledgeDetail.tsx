import { ArrowClockwise, ArrowLeft, CaretRight, FileText, ShieldCheck } from "@phosphor-icons/react";
import { useState } from "react";
import type { Row } from "../../api";
import { Badge, Empty, ErrorNote, Loading, useData } from "../../ui";
import AclDialog from "../../components/AclDialog";
import DocumentUpload from "../../components/DocumentUpload";
import KnowledgeOverview from "../../components/KnowledgeOverview";
import KnowledgeLifecycle from "../../components/KnowledgeLifecycle";
import DocumentDetail from "./DocumentDetail";
import ConfigurationVersions from "./ConfigurationVersions";
import { knowledgePaths } from "./client";
export default function KnowledgeDetail({ kb, back }: { kb: Row; back: () => void }) {
  const [page, setPage] = useState(0),
    [doc, setDoc] = useState<Row | null>(null),
    [acl, setAcl] = useState(false);
  const docs = useData<Row[]>(
    knowledgePaths.documents(kb.id, page),
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
      <KnowledgeLifecycle id={kb.id} onChanged={back} />
      <ConfigurationVersions kb={kb.id} />
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
