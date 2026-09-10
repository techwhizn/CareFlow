import { useState } from "react";
import { post } from "../../api";
import type { Row } from "../../api";
import { ErrorNote, useData } from "../../ui";

export default function ImprovementRequest({
  sourceKind,
  sourceId,
  defaultKb = "",
}: {
  sourceKind: "ANSWER" | "NO_RESULT";
  sourceId: string;
  defaultKb?: string;
}) {
  const bases = useData<Row[]>("/knowledge-bases", []);
  const [kb, setKb] = useState(defaultKb),
    [description, setDescription] = useState(""),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [created, setCreated] = useState("");
  return (
    <div className="query-panel">
      <h3>转为知识改进任务</h3>
      <p>将本次问题和证据交给所选知识库的管理人员复核。</p>
      <label>
        负责知识库
        <select value={kb} onChange={(e) => setKb(e.target.value)}>
          <option value="">请选择</option>
          {bases.data.map((b) => (
            <option key={b.id} value={b.id}>
              {b.name}
            </option>
          ))}
        </select>
      </label>
      <label>
        补充说明
        <textarea
          value={description}
          maxLength={2000}
          onChange={(e) => setDescription(e.target.value)}
        />
      </label>
      <button
        disabled={busy || !kb || !!created}
        onClick={async () => {
          setBusy(true);
          setError("");
          try {
            const task = await post("/improvements", {
              source_kind: sourceKind,
              source_id: sourceId,
              knowledge_base_id: kb,
              description,
            });
            setCreated(task.id);
          } catch (e) {
            setError((e as Error).message);
          } finally {
            setBusy(false);
          }
        }}
      >
        {created ? "改进任务已创建" : "创建改进任务"}
      </button>
      {created && (
        <p role="status">
          可在“知识改进”中查看处理进度；相同来源会复用已有任务。
        </p>
      )}
      <ErrorNote error={error || bases.error} />
    </div>
  );
}
