import ApplicationLimits from "./ApplicationLimits";
import { useEffect, useState } from "react";
import { post, put, request } from "../../api";
import type { Row } from "../../api";
import { ErrorNote, useData } from "../../ui";
import AppCredentials from "../../components/AppCredentials";

const defaults = {
  language: "auto",
  style: "standard",
  maximum_output_tokens: 2048,
  history_rounds: 6,
  history_tokens: 3000,
};
export default function ApplicationEditor({
  app,
  bases,
  owners,
  models,
  done,
}: {
  app: Row;
  bases: Row[];
  owners: Row[];
  models: Row[];
  done: () => void;
}) {
  const configs = useData<Row[]>(`/applications/${app.id}/configurations`, []);
  const publications = useData<Row[]>(
    `/applications/${app.id}/publications`,
    [],
  );
  const [binding, setBinding] = useState<string[]>([]),
    [owner, setOwner] = useState(app.owner_id || ""),
    [custom, setCustom] = useState(false),
    [mode, setMode] = useState("hybrid"),
    [limit, setLimit] = useState(6),
    [minimum, setMinimum] = useState(""),
    [degraded, setDegraded] = useState(false),
    [rank, setRank] = useState(""),
    [generation, setGeneration] = useState(""),
    [policy, setPolicy] = useState({ ...defaults }),
    [error, setError] = useState(""),
    [secret, setSecret] = useState(""),
    [busy, setBusy] = useState(false),
    [loaded, setLoaded] = useState(false);
  async function load(c?: Row) {
    setBusy(true);
    setError("");
    try {
      const active =
        c || configs.data.find((x) => x.id === app.published_configuration);
      const definition = active?.definition;
      setBinding(
        definition?.knowledge_base_ids ||
          (await request<Row[]>(`/applications/${app.id}/bindings`)).map(
            (x) => x.kb_id,
          ),
      );
      setOwner(definition?.owner_id || app.owner_id || "");
      setCustom(!!definition?.retrieval);
      setMode(definition?.retrieval?.mode || "hybrid");
      setLimit(definition?.retrieval?.limit || 6);
      setMinimum(definition?.retrieval?.minimum_rerank_score?.toString() || "");
      setDegraded(definition?.allow_degraded || false);
      setRank(definition?.models?.rerank_profile_id || "");
      setGeneration(definition?.models?.generation_profile_id || "");
      setPolicy(definition?.answer_policy || { ...defaults });
      setLoaded(true);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  useEffect(() => {
    if (!loaded && configs.data.length > 0) void load();
  }, [configs.data]);

  function body() {
    const r = models.find((x) => x.id === rank),
      g = models.find((x) => x.id === generation);
    if (!!rank !== !!generation)
      throw new Error("请同时选择重排模型和生成模型，或都留空继承知识库配置。");
    return {
      revision: app.revision,
      knowledge_base_ids: binding,
      owner_id: owner || null,
      allow_degraded: degraded,
      retrieval: custom
        ? {
            mode,
            limit,
            minimum_rerank_score: minimum === "" ? null : Number(minimum),
            allow_degraded: degraded,
          }
        : null,
      models:
        r && g
          ? {
              rerank_profile_id: r.id,
              rerank_profile_revision: r.revision,
              generation_profile_id: g.id,
              generation_profile_revision: g.revision,
            }
          : null,
      answer_policy: policy,
    };
  }
  return (
    <>
      <ErrorNote error={error || configs.error || publications.error} />
      <p>
        应用 ID：<code>{app.id}</code> · 当前修订 {app.revision}
      </p>
      <p className="notice">
        先为应用授予知识库读取权限。负责人不自动获得额外权限，应用配置也不会扩大资料授权。
      </p>
      <button disabled={busy} onClick={() => void load()}>
        载入当前发布配置
      </button>
      <label>
        负责人
        <select value={owner} onChange={(e) => setOwner(e.target.value)}>
          <option value="">保持当前负责人</option>
          {owners.map((o) => (
            <option key={o.id} value={o.id}>
              {o.name}
            </option>
          ))}
        </select>
      </label>
      <label>
        绑定知识库
        <select
          multiple
          value={binding}
          onChange={(e) =>
            setBinding([...e.target.selectedOptions].map((o) => o.value))
          }
        >
          {bases.map((b) => (
            <option key={b.id} value={b.id}>
              {b.name}
            </option>
          ))}
        </select>
      </label>
      <label>
        <input
          type="checkbox"
          checked={custom}
          onChange={(e) => setCustom(e.target.checked)}
        />
        使用应用独立检索策略
      </label>
      {custom && (
        <>
          <label>
            检索模式
            <select value={mode} onChange={(e) => setMode(e.target.value)}>
              <option value="hybrid">混合检索</option>
              <option value="semantic">语义检索</option>
              <option value="keyword">关键词检索</option>
            </select>
          </label>
          <label>
            最多证据数
            <input
              type="number"
              min={1}
              max={6}
              value={limit}
              onChange={(e) => setLimit(Number(e.target.value))}
            />
          </label>
          <label>
            最低重排分数
            <input
              type="number"
              step="any"
              value={minimum}
              onChange={(e) => setMinimum(e.target.value)}
            />
          </label>
        </>
      )}
      <label>
        <input
          type="checkbox"
          checked={degraded}
          onChange={(e) => setDegraded(e.target.checked)}
        />
        允许显式降级检索
      </label>
      <label>
        重排模型
        <select value={rank} onChange={(e) => setRank(e.target.value)}>
          <option value="">继承知识库查询模型</option>
          {models
            .filter((m) => m.kind === "RERANK")
            .map((m) => (
              <option key={m.id} value={m.id}>
                {m.name} · {m.model}
              </option>
            ))}
        </select>
      </label>
      <label>
        生成模型
        <select
          value={generation}
          onChange={(e) => setGeneration(e.target.value)}
        >
          <option value="">继承知识库查询模型</option>
          {models
            .filter((m) => m.kind === "GENERATION")
            .map((m) => (
              <option key={m.id} value={m.id}>
                {m.name} · {m.model}
              </option>
            ))}
        </select>
      </label>
      <h3>问答约束</h3>
      <label>
        回答语言
        <select
          value={policy.language}
          onChange={(e) => setPolicy({ ...policy, language: e.target.value })}
        >
          <option value="auto">跟随问题语言</option>
          <option value="zh">中文</option>
          <option value="en">英文</option>
        </select>
      </label>
      <label>
        回答风格
        <select
          value={policy.style}
          onChange={(e) => setPolicy({ ...policy, style: e.target.value })}
        >
          <option value="concise">简洁</option>
          <option value="standard">标准</option>
          <option value="detailed">详细</option>
        </select>
      </label>
      {(
        [
          ["maximum_output_tokens", "最大输出 Token", 128, 2048],
          ["history_rounds", "最多历史轮次", 0, 6],
          ["history_tokens", "历史 Token 上限", 0, 3000],
        ] as const
      ).map(([key, label, min, max]) => (
        <label key={key}>
          {label}
          <input
            type="number"
            min={min}
            max={max}
            value={policy[key]}
            onChange={(e) =>
              setPolicy({ ...policy, [key]: Number(e.target.value) })
            }
          />
        </label>
      ))}
      {!loaded && app.published && (
        <p className="notice">
          尚未载入当前配置；填写表单后将保存新的完整配置。
        </p>
      )}
      <div className="button-row">
        <button
          disabled={busy || !binding.length}
          onClick={async () => {
            setBusy(true);
            setError("");
            try {
              await post(`/applications/${app.id}/configurations`, body());
              await configs.reload();
            } catch (e) {
              setError((e as Error).message);
            } finally {
              setBusy(false);
            }
          }}
        >
          保存草稿
        </button>
        <button
          disabled={busy || !binding.length}
          onClick={async () => {
            setBusy(true);
            setError("");
            try {
              await put(`/applications/${app.id}/publication`, body());
              done();
            } catch (e) {
              setError((e as Error).message);
            } finally {
              setBusy(false);
            }
          }}
        >
          发布新配置
        </button>
      </div>
      <h3>配置版本</h3>
      {configs.data.map((c) => (
        <div key={c.id} className="section-title">
          <span>
            {c.id === app.published_configuration
              ? "当前发布"
              : c.state === "DRAFT"
                ? "草稿"
                : "曾发布"}{" "}
            · {c.id.slice(0, 8)}
          </span>
          <button disabled={busy} onClick={() => void load(c)}>
            载入编辑
          </button>
          <button
            disabled={busy}
            onClick={async () => {
              setBusy(true);
              setError("");
              try {
                await post(
                  `/applications/${app.id}/configuration-publications`,
                  { configuration_id: c.id, revision: app.revision },
                );
                done();
              } catch (e) {
                setError((e as Error).message);
              } finally {
                setBusy(false);
              }
            }}
          >
            {c.state === "DRAFT" ? "发布草稿" : "回滚此配置"}
          </button>
        </div>
      ))}
      <details>
        <summary>发布记录</summary>
        {publications.data.map((p) => (
          <p key={p.id}>
            修订 {p.revision} · 配置 {p.configuration_id} ·{" "}
            {new Date(p.created_at).toLocaleString()}
          </p>
        ))}
      </details>
      <ApplicationLimits applicationId={app.id} />
      <AppCredentials applicationId={app.id} onIssued={setSecret} />
      {secret && (
        <label>
          凭证仅显示一次，请安全保存
          <textarea readOnly value={secret} />
        </label>
      )}
    </>
  );
}
