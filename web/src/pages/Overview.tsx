import {
  ArrowRight,
  Books,
  CaretRight,
  CheckCircle,
} from "@phosphor-icons/react";
import type { Row } from "../api";
import type { Page } from "../navigation";
import { Badge, Empty, ErrorNote, Loading, useData } from "../ui";
export default function Overview({ go }: { go: (p: Page) => void }) {
  const k = useData<Row[]>("/knowledge-bases", []),
    j = useData<Row[]>("/jobs", []);
  return (
    <>
      <div className="welcome">
        <div>
          <span className="eyebrow">YOUR KNOWLEDGE WORKSPACE</span>
          <h1>知识有序，答案有据。</h1>
          <p>管理企业资料，将分散的信息沉淀为可复用的知识。</p>
          <button className="primary" onClick={() => go("knowledge")}>
            管理知识库
            <ArrowRight />
          </button>
        </div>
        <div className="knowledge-art" aria-hidden="true">
          <div className="art-page">
            <span />
            <span />
            <span />
            <span />
            <span />
          </div>
          <div className="art-lines" />
          <div className="art-chunk">
            <Books size={24} />
            <b>可追溯的知识</b>
            <span>文档 → 切片 → 证据</span>
            <CheckCircle size={20} weight="fill" />
          </div>
        </div>
      </div>
      <ErrorNote error={k.error || j.error} />
      <div className="metrics">
        <div>
          <p>知识库</p>
          <strong>{k.loading ? "—" : k.data.length}</strong>
          <span>当前可访问</span>
        </div>
        <div>
          <p>待处理任务</p>
          <strong>
            {j.loading
              ? "—"
              : j.data.filter((x) => ["QUEUED", "RUNNING"].includes(x.state))
                  .length}
          </strong>
          <span>持续追踪处理进度</span>
        </div>
        <div>
          <p>失败任务</p>
          <strong>
            {j.loading
              ? "—"
              : j.data.filter((x) => x.state === "FAILED").length}
          </strong>
          <span>检查失败阶段与原因</span>
        </div>
      </div>
      <div className="section-title">
        <h2>知识库概览</h2>
        <button className="text-button" onClick={() => go("knowledge")}>
          查看全部
          <ArrowRight />
        </button>
      </div>
      {k.loading ? (
        <Loading />
      ) : !k.data.length ? (
        <div className="panel">
          <Empty
            title="从第一份资料开始"
            detail="创建知识库，上传资料，检查切片后发布知识。"
          />
        </div>
      ) : (
        <div className="kb-grid">
          {k.data.slice(0, 4).map((x) => (
            <button
              className="kb-card"
              key={x.id}
              onClick={() => go("knowledge")}
            >
              <div className="kb-icon">
                <Books size={24} />
              </div>
              <Badge value={x.status} />
              <h3>{x.name}</h3>
              <p>{x.description || "尚未填写知识库说明"}</p>
              <footer>
                进入知识库
                <CaretRight />
              </footer>
            </button>
          ))}
        </div>
      )}
      <div className="steps">
        <div>
          <span>01</span>
          <h3>整理资料</h3>
          <p>上传文件并检查解析与来源位置。</p>
        </div>
        <div>
          <span>02</span>
          <h3>审核发布</h3>
          <p>修订知识切片，完成索引后发布。</p>
        </div>
        <div>
          <span>03</span>
          <h3>检验答案</h3>
          <p>查看召回、重排和最终引用证据。</p>
        </div>
      </div>
    </>
  );
}
