import { ArrowRight, Books, MagnifyingGlass, Plus } from "@phosphor-icons/react";
import React, { useState } from "react";
import type { Row } from "../api";
import { Badge, Dialog, Empty, ErrorNote, Loading, useData } from "../ui";
import KnowledgeDetail from "../features/knowledge/KnowledgeDetail";
import { knowledgeClient, knowledgePaths } from "../features/knowledge/client";
export default function Knowledge() {
  const k = useData<Row[]>(knowledgePaths.list, []);
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
      await knowledgeClient.create({
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
