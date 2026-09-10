import { useEffect, useRef, useState } from "react";
import { FileText } from "@phosphor-icons/react";
import { downloadSource, request } from "../../api";
import type { Row } from "../../api";
import { ErrorNote } from "../../ui";
import SourcePreview from "../knowledge/SourcePreview";

function location(raw: unknown): Record<string, unknown> {
  try {
    const value: unknown = typeof raw === "string" ? JSON.parse(raw) : raw;
    if (value && typeof value === "object" && !Array.isArray(value))
      return value as Record<string, unknown>;
  } catch {
    /* Missing positions are shown explicitly by SourcePreview. */
  }
  return {};
}

export default function AnswerCitations({
  answerId,
  evidence,
}: {
  answerId: string;
  evidence: Row[];
}) {
  const [selected, setSelected] = useState<Row | null>(null);
  const [error, setError] = useState("");
  const controller = useRef<AbortController | null>(null);
  useEffect(() => {
    setSelected(null);
    setError("");
    return () => controller.current?.abort();
  }, [answerId]);
  return (
    <div className="citations">
      <h3>引用证据</h3>
      <p className="muted">
        请核对各来源的适用时间；资料互相矛盾时，应保留不同来源并由知识管理员确认。
      </p>
      {evidence.map((c) => (
        <details key={c.id} id={`citation-${c.id}`}>
          <summary>
            <FileText />
            {c.title}
            <code>{c.id.slice(0, 8)}</code>
          </summary>
          <p>{c.content}</p>
          <small>
            文档 {c.document_id} · 版本 {c.version_id} · 切片修订 {c.revision}
          </small>
          <p className="muted">
            文档适用时间：{c.document_valid_from || "未限定开始"} 至{" "}
            {c.document_valid_until || "未限定结束"}
            <br />
            版本适用时间：{c.version_valid_from || "未限定开始"} 至{" "}
            {c.version_valid_until || "未限定结束"}
          </p>
          <button
            disabled={!answerId}
            onClick={async () => {
              controller.current?.abort();
              const control = new AbortController();
              controller.current = control;
              setSelected(null);
              setError("");
              try {
                const source = await request<Row>(
                  `/answers/${answerId}/citations/${c.id}`,
                  { signal: control.signal },
                );
                if (controller.current === control) setSelected(source);
              } catch (e) {
                if (!control.signal.aborted) setError((e as Error).message);
              }
            }}
          >
            定位引用原文
          </button>
        </details>
      ))}
      <ErrorNote error={error} />
      {selected && (
        <section aria-label="引用原文" aria-live="polite">
          <h3>{selected.title} · 引用原文</h3>
          <p>
            {selected.source_snapshot
              ? "以下是生成回答时保存的原文与位置快照。"
              : "此历史答案没有原文快照，下方为当前仍可访问的同版本来源，请核对修订差异。"}
          </p>
          {(selected.source_chunks as Row[]).map((chunk) => (
            <div key={chunk.id}>
              <small>
                切片 {chunk.id} · 修订 {chunk.revision}
              </small>
              <SourcePreview
                chunk={chunk}
                location={location(chunk.location)}
              />
            </div>
          ))}
          <button
            onClick={async () => {
              setError("");
              try {
                await downloadSource(
                  selected.version_id,
                  selected.filename || selected.title,
                );
              } catch (e) {
                setError((e as Error).message);
              }
            }}
          >
            下载原文件（需下载权限）
          </button>
          <button onClick={() => setSelected(null)}>关闭原文</button>
        </section>
      )}
    </div>
  );
}
