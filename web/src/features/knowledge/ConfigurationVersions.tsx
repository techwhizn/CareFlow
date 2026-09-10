import { useState } from "react";
import { post } from "../../api";
import { Dialog, ErrorNote, Loading, useData } from "../../ui";

type Kind = "EMBEDDING" | "RERANK" | "GENERATION";
type Model = { id: string; name: string; kind: Kind; model: string; revision: number; external_processing: boolean };
type Definition = {
  name: string;
  parsing: { pdf_page_limit: number };
  chunking: { target: number; maximum: number; overlap: number; strategy: string; include_context: boolean; model_tokenizer: string; model_maximum: number };
  retrieval: { mode: string; limit: number; minimum_rerank_score: number | null; allow_degraded: boolean };
  models: { embedding_profile_id: string; rerank_profile_id: string; generation_profile_id: string;
    embedding_profile_revision: number; rerank_profile_revision: number; generation_profile_revision: number };
};
type Version = { id: string; definition: Definition; ever_published: boolean; created_at: string };
type Versions = { published_configuration: string; revision: number; versions: Version[] };
type Impact = { revision: number; reparse_required: boolean; reindex_required: boolean; model_snapshot_changed: boolean };

export default function ConfigurationVersions({ kb }: { kb: string }) {
  const [open, setOpen] = useState(false);
  return <section className="panel">
    <div className="section-title"><h2>处理与查询配置</h2><button onClick={() => setOpen(true)}>管理配置版本</button></div>
    <p className="muted">配置先保存为草稿，再发布。新上传和重新处理使用已发布配置；已有文档保留原处理版本。</p>
    {open && <ConfigurationDialog kb={kb} close={() => setOpen(false)} />}
  </section>;
}
function ConfigurationDialog({ kb, close }: { kb: string; close: () => void }) {
  const base = `/knowledge-bases/${kb}`;
  const versions = useData<Versions>(`${base}/configurations`, { published_configuration: "", revision: 0, versions: [] });
  const models = useData<Model[]>(`${base}/configuration-models`, []);
  const [editing, setEditing] = useState<Version | "new" | null>(null);
  const [publishing, setPublishing] = useState<Version | null>(null);
  return <Dialog title="知识库配置版本" close={close}>
    <ErrorNote error={versions.error || models.error} />
    {versions.loading || models.loading ? <Loading /> : !versions.error && !models.error && <>
      <p>发布修订 {versions.data.revision} · {versions.data.published_configuration ? "已启用版本配置" : "尚未配置，现有任务使用部署配置"}</p>
      <button onClick={() => setEditing("new")}>创建配置草稿</button>
      {!versions.data.versions.length ? <p className="muted">暂无配置版本。先由企业管理员在模型配置中添加生成、Embedding和Rerank档案。</p> :
        <div className="table-wrap"><table><thead><tr><th>配置</th><th>状态</th><th>切片 Token</th><th>操作</th></tr></thead><tbody>
          {versions.data.versions.map(version => <tr key={version.id}>
            <td>{version.definition.name}<small>{version.id.slice(0, 8)}</small></td>
            <td>{version.id === versions.data.published_configuration ? "当前发布" : version.ever_published ? "历史发布" : "草稿"}</td>
            <td>{version.definition.chunking.target} / {version.definition.chunking.maximum}</td>
            <td><button onClick={() => setEditing(version)}>查看并另存草稿</button>
              <button disabled={version.id === versions.data.published_configuration} onClick={() => setPublishing(version)}>{version.ever_published ? "预览回滚影响" : "预览发布影响"}</button></td>
          </tr>)}
        </tbody></table></div>}
      <p className="muted">展示最近100个版本。模型档案被更新时，保存会校验你看到的修订；回滚不恢复历史权限。</p>
    </>}
    {editing && <DefinitionForm models={models.data} initial={editing === "new" ? undefined : editing.definition} close={() => setEditing(null)} save={async definition => {
      await post(`${base}/configurations`, definition); setEditing(null); await versions.reload();
    }} />}
    {publishing && <PublishConfiguration base={base} version={publishing} close={() => setPublishing(null)} saved={async () => {
      setPublishing(null); await versions.reload();
    }} />}
  </Dialog>;
}
function DefinitionForm({ models, initial, close, save }: { models: Model[]; initial?: Definition; close: () => void; save: (definition: Definition) => Promise<void> }) {
  const [error, setError] = useState(""), [busy, setBusy] = useState(false);
  const kindFields = [["EMBEDDING", "embedding", "Embedding模型"], ["RERANK", "rerank", "重排模型"], ["GENERATION", "generation", "生成模型"]] as const;
  return <Dialog title="配置草稿" close={close}><ErrorNote error={error} />
    <form onSubmit={async event => {
      event.preventDefault(); const data = new FormData(event.currentTarget); setBusy(true); setError("");
      const number = (key: string) => Number(data.get(key));
      const id = (kind: string) => String(data.get(kind));
      const revision = (kind: string) => models.find(model => model.id === id(kind))!.revision;
      const score = String(data.get("minimum_rerank_score"));
      try { await save({ name: String(data.get("name")), parsing: { pdf_page_limit: number("pdf_page_limit") },
        chunking: { target: number("target"), maximum: number("maximum"), overlap: number("overlap"), strategy: String(data.get("strategy")), include_context: data.has("include_context"), model_tokenizer: String(data.get("model_tokenizer")), model_maximum: number("model_maximum") },
        retrieval: { mode: String(data.get("mode")), limit: number("limit"), minimum_rerank_score: score === "" ? null : Number(score), allow_degraded: data.has("allow_degraded") },
        models: { embedding_profile_id: id("embedding"), rerank_profile_id: id("rerank"), generation_profile_id: id("generation"),
          embedding_profile_revision: revision("embedding"), rerank_profile_revision: revision("rerank"), generation_profile_revision: revision("generation") } });
      } catch (cause) { setError((cause as Error).message); } finally { setBusy(false); }
    }}>
      <label>配置名称<input name="name" required maxLength={200} defaultValue={initial?.name || "知识库配置"} /></label>
      {kindFields.map(([kind, key, label]) => <label key={key}>{label}<select required name={key} defaultValue={initial?.models[`${key}_profile_id`] || ""}>
        <option value="" disabled>选择已登记模型</option>
        {models.filter(model => model.kind === kind).map(model => <option key={model.id} value={model.id}>{model.name} · {model.model} · 修订{model.revision}{model.external_processing ? " · 外部处理" : " · 本地处理"}</option>)}
      </select></label>)}
      <p className="muted">模型信息取自当前档案列表；另存旧配置时也会采用这里显示的当前档案修订。密钥不会返回浏览器。</p>
      <label>PDF页数上限<input name="pdf_page_limit" type="number" required min={1} max={500} defaultValue={initial?.parsing.pdf_page_limit ?? 500} /></label>
      <label>切片目标Token<input name="target" type="number" required min={1} max={600} defaultValue={initial?.chunking.target ?? 200} /></label>
      <label>切片Token上限<input name="maximum" type="number" required min={1} max={600} defaultValue={initial?.chunking.maximum ?? 300} /></label>
      <label>重叠Token<input name="overlap" type="number" required min={0} max={599} defaultValue={initial?.chunking.overlap ?? 30} /></label>
      <label>切片方式<select name="strategy" defaultValue={initial?.chunking.strategy ?? "recursive"}><option value="recursive">按段落和句子递归细分</option><option value="token">按Token预算细分</option></select></label>
      <label><input name="include_context" type="checkbox" defaultChecked={initial?.chunking.include_context ?? true} />每个片段保留标题和表头上下文</label>
      <label>模型输入计数<select name="model_tokenizer" defaultValue={initial?.chunking.model_tokenizer ?? "cl100k_base"}><option value="cl100k_base">cl100k_base（模型须使用相同计数方式）</option><option value="provider">模型原生计数（服务须支持）</option></select></label>
      <label>模型输入Token上限<input name="model_maximum" type="number" required min={1} max={131072} defaultValue={initial?.chunking.model_maximum ?? 600} /></label>
      <label>默认检索模式<select name="mode" defaultValue={initial?.retrieval.mode ?? "hybrid"}><option value="hybrid">混合检索</option><option value="semantic">语义检索</option><option value="keyword">关键词检索</option></select></label>
      <label>最多证据数<input name="limit" type="number" required min={1} max={6} defaultValue={initial?.retrieval.limit ?? 6} /></label>
      <label>最低重排分数（可选）<input name="minimum_rerank_score" type="number" step="any" defaultValue={initial?.retrieval.minimum_rerank_score ?? ""} /></label>
      <label><input name="allow_degraded" type="checkbox" defaultChecked={initial?.retrieval.allow_degraded ?? false} />允许明确标记的检索降级</label>
      <p className="muted">标题与表头计入预算。原生计数采用配置上限与服务实际窗口中的较小值，超限片段继续细分；不能容纳上下文或计数服务不可用时明确失败。BGE请选择原生计数。</p>
      <button className="primary" disabled={busy || kindFields.some(([kind]) => !models.some(model => model.kind === kind))}>保存草稿</button>
    </form>
  </Dialog>;
}
function PublishConfiguration({ base, version, close, saved }: { base: string; version: Version; close: () => void; saved: () => Promise<void> }) {
  const impact = useData<Impact | null>(`${base}/configurations/${version.id}/impact`, null);
  const [error, setError] = useState(""), [busy, setBusy] = useState(false);
  return <Dialog title="发布配置影响" close={close}><ErrorNote error={error || impact.error} />
    {impact.loading ? <Loading /> : impact.data && <form onSubmit={async event => {
      event.preventDefault(); const data = new FormData(event.currentTarget); setBusy(true); setError("");
      try { await post(`${base}/configuration-publications`, { configuration_id: version.id, revision: impact.data!.revision, reason: String(data.get("reason")) }); await saved(); }
      catch (cause) { setError((cause as Error).message); } finally { setBusy(false); }
    }}>
      <p>即将发布：{version.definition.name}</p>
      <p>{impact.data.reparse_required ? "解析或切片配置有变化，已有资料需创建新的处理版本才能采用。" : "解析和切片参数不变。"}</p>
      {impact.data.reindex_required && <p>新配置用于已有资料时需要建立新索引；当前发布和旧索引保持可用。</p>}
      {impact.data.model_snapshot_changed && <p>模型快照有变化；已有索引继续使用原模型，新处理版本使用新配置。</p>}
      <p>发布会影响之后的查询策略和新任务。不会自动替换已发布文档；应用新处理结果仍需审核、索引和文档发布。</p>
      <label>变更原因<textarea name="reason" required maxLength={1000} /></label>
      <button className="primary" disabled={busy}>确认发布配置</button>
    </form>}
  </Dialog>;
}
