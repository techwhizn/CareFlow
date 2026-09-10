import AppCredentials from "../components/AppCredentials";
import { ArrowRight, PlugsConnected, Plus } from "@phosphor-icons/react";
import { useState } from "react";
import type { Row } from "../api";
import { post, put, request } from "../api";
import { Badge, Dialog, Empty, ErrorNote, useData } from "../ui";
export default function AppsPage() {
  const apps = useData<Row[]>("/applications", []),
    kbs = useData<Row[]>("/knowledge-bases", []),
    [creating, setCreating] = useState(false),
    [selected, setSelected] = useState<Row | null>(null),
    [binding, setBinding] = useState<string[]>([]),
    [configurations, setConfigurations] = useState<Row[]>([]),
    [secret, setSecret] = useState(""),
    [error, setError] = useState("");
  return (
    <>
      <div className="page-heading">
        <div>
          <h1>应用中心</h1>
          <p>应用独立配置与授权，共享知识库能力。</p>
        </div>
        <button className="primary" onClick={() => setCreating(true)}>
          <Plus />
          创建应用
        </button>
      </div>
      <ErrorNote error={error || apps.error} />
      {!apps.data.length ? (
        <Empty
          icon={PlugsConnected}
          title="连接知识与业务"
          detail="创建知识问答应用，授权并绑定知识库后发布。"
        />
      ) : (
        <div className="kb-grid">
          {apps.data.map((a) => (
            <button
              key={a.id}
              className="kb-card"
              onClick={async () => {
                setSelected(a);
                setSecret("");
                try {
                  const rows = await request<Row[]>(
                    `/applications/${a.id}/bindings`,
                  );
                  setBinding(rows.map((x) => x.kb_id));
                  setConfigurations(
                    await request<Row[]>(
                      `/applications/${a.id}/configurations`,
                    ),
                  );
                } catch (e) {
                  setError((e as Error).message);
                }
              }}
            >
              <div className="kb-icon">
                <PlugsConnected size={24} />
              </div>
              <Badge value={a.published ? "已发布" : "草稿"} />
              <h3>{a.name}</h3>
              <p>{a.description || "知识问答应用"}</p>
              <footer>
                配置与 API Key
                <ArrowRight />
              </footer>
            </button>
          ))}
        </div>
      )}
      {creating && (
        <Dialog title="创建知识问答应用" close={() => setCreating(false)}>
          <form
            onSubmit={async (e) => {
              e.preventDefault();
              const f = new FormData(e.currentTarget);
              try {
                await post("/applications", {
                  name: f.get("name"),
                  description: f.get("description"),
                });
                setCreating(false);
                await apps.reload();
              } catch (e) {
                setError((e as Error).message);
              }
            }}
          >
            <label>
              应用名称
              <input name="name" required maxLength={200} />
            </label>
            <label>
              说明
              <textarea name="description" />
            </label>
            <button className="primary">创建应用</button>
          </form>
        </Dialog>
      )}
      {selected && (
        <Dialog title={selected.name} close={() => setSelected(null)}>
          <ErrorNote error={error} />
          <p>
            应用 ID：<code>{selected.id}</code>
          </p>
          <div className="notice">
            先在知识库授权管理中授予以上应用 ID
            读取权限，再绑定发布。应用配置不能扩大权限。
          </div>
          <label>
            绑定知识库
            <select
              multiple
              value={binding}
              onChange={(e) =>
                setBinding([...e.target.selectedOptions].map((o) => o.value))
              }
            >
              {kbs.data.map((k) => (
                <option key={k.id} value={k.id}>
                  {k.name}
                </option>
              ))}
            </select>
          </label>
          <div className="button-row">
            <button
              onClick={async () => {
                try {
                  await post(`/applications/${selected.id}/configurations`, {
                    revision: selected.revision,
                    knowledge_base_ids: binding,
                    allow_degraded: false,
                  });
                  setConfigurations(
                    await request<Row[]>(
                      `/applications/${selected.id}/configurations`,
                    ),
                  );
                } catch (e) {
                  setError((e as Error).message);
                }
              }}
            >
              保存草稿
            </button>
            <button
              className="primary"
              onClick={async () => {
                try {
                  await put(`/applications/${selected.id}/publication`, {
                    revision: selected.revision,
                    knowledge_base_ids: binding,
                    allow_degraded: false,
                  });
                  setSelected(null);
                  await apps.reload();
                } catch (e) {
                  setError((e as Error).message);
                }
              }}
            >
              发布配置
            </button>

          </div>
          <AppCredentials applicationId={selected.id} onIssued={setSecret} />
          {!!configurations.length && (
            <div className="configurations">
              <h3>配置版本</h3>
              {configurations.map((c) => (
                <div className="section-title" key={c.id}>
                  <span>
                    <code>{c.id.slice(0, 8)}</code> ·{" "}
                    {c.state === "DRAFT" ? "草稿" : "曾发布"}
                  </span>
                  <button
                    onClick={async () => {
                      try {
                        await post(
                          `/applications/${selected.id}/configuration-publications`,
                          {
                            configuration_id: c.id,
                            revision: selected.revision,
                          },
                        );
                        setSelected(null);
                        await apps.reload();
                      } catch (e) {
                        setError((e as Error).message);
                      }
                    }}
                  >
                    {c.state === "DRAFT" ? "发布此草稿" : "回滚此配置"}
                  </button>
                </div>
              ))}
            </div>
          )}
          {secret && (
            <label>
              仅显示一次，请安全保存
              <textarea readOnly value={secret} />
              <small>有效期 90 天，可在凭证管理中撤销。</small>
            </label>
          )}
        </Dialog>
      )}
    </>
  );
}
