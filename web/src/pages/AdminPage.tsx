import { Plus } from "@phosphor-icons/react";
import { useState } from "react";
import type { Row } from "../api";
import { post, put, request } from "../api";
import type { Page } from "../navigation";
import { nav } from "../navigation";
import { DataTable, Dialog, ErrorNote, Loading, useData } from "../ui";
export default function AdminPage({ page }: { page: Page }) {
  const path =
      page === "usage" ? "/usage" : page === "members" ? "/members" : "/audit",
    data = useData<any>(path, page === "usage" ? null : []),
    keys = useData<Row[]>("/credentials", []),
    [error, setError] = useState(""),
    [secret, setSecret] = useState(""),
    [creating, setCreating] = useState(false);
  return (
    <>
      <div className="page-heading">
        <div>
          <h1>{nav.find((n) => n.id === page)?.label}</h1>
          <p>
            {page === "usage"
              ? "查询已结算用量与额度预占，调整均保留审计。"
              : page === "members"
                ? "管理企业成员与访问凭证。"
                : "跟踪关键资源与权限变更。"}
          </p>
        </div>
        {page === "members" && (
          <button className="primary" onClick={() => setCreating(true)}>
            <Plus />
            添加成员
          </button>
        )}
      </div>
      <ErrorNote error={error || data.error} />
      {data.loading ? (
        <Loading />
      ) : page === "usage" && data.data ? (
        <>
          <div className="metrics">
            <div>
              <p>查询额度</p>
              <strong>{data.data.quota.query_limit}</strong>
              <span>当前企业</span>
            </div>
            <div>
              <p>已使用</p>
              <strong>{data.data.quota.queries_used}</strong>
              <span>成功结算</span>
            </div>
            <div>
              <p>预占中</p>
              <strong>{data.data.quota.queries_reserved}</strong>
              <span>正在处理</span>
            </div>
          </div>
          <form
            className="inline-form"
            onSubmit={async (e) => {
              e.preventDefault();
              const f = new FormData(e.currentTarget);
              try {
                await put("/quota", {
                  limit: Number(f.get("limit")),
                  reason: f.get("reason"),
                });
                await data.reload();
              } catch (e) {
                setError((e as Error).message);
              }
            }}
          >
            <label>
              调整总额度
              <input name="limit" type="number" min="0" required />
            </label>
            <label>
              调整原因
              <input name="reason" required />
            </label>
            <button>保存额度</button>
          </form>
          <DataTable
            rows={data.data.events}
            fields={["resource_type", "amount", "state", "created_at"]}
          />
        </>
      ) : page === "members" ? (
        <>
          <DataTable
            rows={data.data || []}
            fields={["name", "role", "id", "active"]}
            action={(r) =>
              r.role !== "OWNER" && r.active ? (
                <button
                  onClick={async () => {
                    if (
                      !window.confirm(
                        `禁用成员 ${r.name} 后，其凭证立即失效。继续？`,
                      )
                    )
                      return;
                    try {
                      await request(`/members/${r.id}`, { method: "DELETE" });
                      await data.reload();
                      await keys.reload();
                    } catch (e) {
                      setError((e as Error).message);
                    }
                  }}
                >
                  禁用
                </button>
              ) : null
            }
          />
          <div className="section-title">
            <h2>访问凭证</h2>
          </div>
          <DataTable
            rows={keys.data}
            fields={["kind", "subject_id", "active", "expires_at"]}
            action={(r) =>
              r.active ? (
                <button
                  onClick={async () => {
                    if (
                      !window.confirm(
                        "撤销后使用此凭证的请求会失败，确认继续？",
                      )
                    )
                      return;
                    try {
                      await request(`/credentials/${r.id}`, {
                        method: "DELETE",
                      });
                      await keys.reload();
                    } catch (e) {
                      setError((e as Error).message);
                    }
                  }}
                >
                  撤销
                </button>
              ) : null
            }
          />
        </>
      ) : (
        <DataTable
          rows={data.data || []}
          fields={["action", "actor_id", "resource_id", "created_at"]}
        />
      )}
      {creating && (
        <Dialog title="添加成员" close={() => setCreating(false)}>
          <form
            onSubmit={async (e) => {
              e.preventDefault();
              const f = new FormData(e.currentTarget);
              try {
                const r = await post("/members", {
                  name: f.get("name"),
                  role: f.get("role"),
                });
                setSecret(r.token);
                setCreating(false);
                await data.reload();
                await keys.reload();
              } catch (e) {
                setError((e as Error).message);
              }
            }}
          >
            <label>
              姓名
              <input name="name" required />
            </label>
            <label>
              角色
              <select name="role">
                <option value="USER">使用者</option>
                <option value="KNOWLEDGE_MANAGER">知识管理员</option>
                <option value="DEVELOPER">应用开发者</option>
                <option value="ADMIN">企业管理员</option>
              </select>
            </label>
            <button className="primary">创建成员</button>
          </form>
        </Dialog>
      )}
      {secret && (
        <Dialog title="成员访问凭证" close={() => setSecret("")}>
          <p>凭证仅显示一次，请通过安全渠道交给该成员。</p>
          <textarea readOnly value={secret} />
        </Dialog>
      )}
    </>
  );
}
