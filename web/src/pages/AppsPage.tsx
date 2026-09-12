import { ArrowRight, PlugsConnected, Plus } from "@phosphor-icons/react";
import { useState } from "react";
import type { Row } from "../api";
import { post, request } from "../api";
import { Badge, Dialog, Empty, ErrorNote, useData } from "../ui";
import ApplicationEditor from "../features/applications/ApplicationEditor";

export default function AppsPage() {
  const apps = useData<Row[]>("/applications", []),
    bases = useData<Row[]>("/knowledge-bases", []),
    owners = useData<Row[]>("/applications/owner-options", []),
    models = useData<Row[]>("/applications/model-options", []);
  const [creating, setCreating] = useState(false),
    [selected, setSelected] = useState<Row | null>(null),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  return (
    <>
      <div className="page-heading">
        <div>
          <h1>应用中心</h1>
          <p>知识问答应用独立配置，共享已授权知识库。</p>
        </div>
        <button className="primary" onClick={() => setCreating(true)}>
          <Plus />
          创建应用
        </button>
      </div>
      <ErrorNote error={error || apps.error || owners.error || models.error} />
      {!apps.data.length ? (
        <Empty
          icon={PlugsConnected}
          title="连接知识与业务"
          detail="创建知识问答应用，配置模型、策略和问答约束。"
        />
      ) : (
        <div className="kb-grid">
          {apps.data.map((app) => (
            <div className="kb-card-wrap" key={app.id}><button className="kb-card" onClick={() => setSelected(app)}>
              <div className="kb-icon"><PlugsConnected size={22} /></div>
              <Badge value={app.published ? "已发布" : "草稿"} />
              <h3>{app.name}</h3>
              <p>{app.description || "知识问答应用"}</p>
              <small className="app-owner">
                负责人：
                {owners.data.find((o) => o.id === app.owner_id)?.name ||
                  "待配置"}
              </small>
              <footer>打开应用<ArrowRight /></footer>
            </button><button className="kb-delete" disabled={busy} onClick={async event => {
              event.stopPropagation();
              if (!window.confirm(`确认删除应用“${app.name}”？已签发的 API Key 将失效，应用将停止访问。`)) return;
              setBusy(true); setError("");
              try { await request(`/applications/${app.id}`, { method: "DELETE" }); await apps.reload(); }
              catch (e) { setError((e as Error).message); }
              finally { setBusy(false); }
            }}>删除应用</button></div>
          ))}
        </div>
      )}
      {creating && (
        <Dialog title="创建知识问答应用" close={() => setCreating(false)}>
          <form
            onSubmit={async (e) => {
              e.preventDefault();
              const data = new FormData(e.currentTarget);
              try {
                await post("/applications", {
                  name: data.get("name"),
                  description: data.get("description"),
                  owner_id: data.get("owner") || null,
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
              <textarea name="description" maxLength={2000} />
            </label>
            <label>
              负责人
              <select name="owner">
                <option value="">当前创建者</option>
                {owners.data.map((o) => (
                  <option key={o.id} value={o.id}>
                    {o.name}
                  </option>
                ))}
              </select>
            </label>
            <button className="primary">创建应用</button>
          </form>
        </Dialog>
      )}
      {selected && (
        <Dialog title={selected.name} close={() => setSelected(null)}>
          <ApplicationEditor
            app={selected}
            bases={bases.data}
            owners={owners.data}
            models={models.data}
            done={() => {
              setSelected(null);
              void apps.reload();
            }}
          />
        </Dialog>
      )}
    </>
  );
}
