import { useState } from "react";
import { UploadSimple } from "@phosphor-icons/react";
import type { Row } from "../api";
import { request } from "../api";
import { ErrorNote } from "../ui";
import { CostView } from "../features/billing/CostView";

type Item = { name: string; state: string; detail: string; quote?: Row };
export default function DocumentUpload({
  knowledgeBaseId,
  documentId,
  onUploaded,
}: {
  knowledgeBaseId: string;
  documentId?: string;
  onUploaded: (result?: Row) => Promise<void>;
}) {
  const [items, setItems] = useState<Item[]>([]),
    [selected, setSelected] = useState<File[]>([]);
  const [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  function update(index: number, patch: Partial<Item>) {
    setItems((previous) =>
      previous.map((item, i) => (i === index ? { ...item, ...patch } : item)),
    );
  }
  async function preview(files: File[]) {
    if (!files.length || busy) return;
    if (files.length > (documentId ? 1 : 20)) {
      setError(documentId ? "每次选择一个新版本文件" : "单批最多20个文件");
      return;
    }
    setBusy(true);
    setError("");
    setSelected(files);
    setItems(
      files.map((f) => ({ name: f.name, state: "正在预估", detail: "" })),
    );
    try {
      for (let i = 0; i < files.length; i++) {
        if (files[i].size === 0 || files[i].size > 50 * 1024 * 1024) {
          update(i, { state: "预估失败", detail: "文件为空或超过50MiB" });
          continue;
        }
        const data = new FormData();
        data.append("file", files[i]);
        try {
          const quote = await request(
            `/knowledge-bases/${knowledgeBaseId}/import-estimate`,
            { method: "POST", body: data },
          );
          update(i, { state: "待导入", quote });
        } catch (e) {
          update(i, { state: "预估失败", detail: (e as Error).message });
        }
      }
    } finally {
      setBusy(false);
    }
  }
  async function upload() {
    if (busy || !selected.length) return;
    setBusy(true);
    setError("");
    const failed: File[] = [];
    const uploaded: Row[] = [];
    try {
      for (let i = 0; i < selected.length; i++) {
        if (!items[i]?.quote) continue;
        update(i, { state: "上传中" });
        const data = new FormData();
        data.append("file", selected[i]);
        data.append(
          "billing_revision",
          String(items[i].quote!.billing_revision),
        );
        const path = documentId
          ? `/documents/${documentId}/versions`
          : `/knowledge-bases/${knowledgeBaseId}/documents`;
        try {
          const result = await request<Row>(path, { method: "POST", body: data });
          uploaded.push(result);
          update(i, {
            state: "已接收",
            detail: "等待解析；不代表处理完成或已发布",
          });
        } catch (e) {
          failed.push(selected[i]);
          update(i, { state: "失败", detail: (e as Error).message });
        }
      }
      setSelected(failed);
      await onUploaded(uploaded[0]);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <ErrorNote error={error} />
      <label className={`upload-zone ${busy ? "disabled" : ""}`}>
        <UploadSimple size={28} />
        <div>
          <strong>
            {busy
              ? "正在处理…"
              : documentId
                ? "选择新版本文件并预估"
                : "选择资料并预估"}
          </strong>
          <p>PDF、DOCX、Markdown、TXT、CSV、XLSX、PNG、JPEG · 单文件50MiB</p>
        </div>
        <input
          type="file"
          multiple={!documentId}
          disabled={busy}
          accept=".pdf,.docx,.md,.txt,.csv,.xlsx,.png,.jpg,.jpeg"
          onChange={(e) => {
            const files = Array.from(e.target.files || []);
            e.target.value = "";
            void preview(files);
          }}
        />
      </label>
      {!!items.length && (
        <div aria-live="polite">
          <h3>导入预估与结果</h3>
          {items.map((item, i) => (
            <div key={i}>
              <p>
                <b>{item.name}</b> · {item.state} {item.detail}
              </p>
              {item.quote && (
                <>
                  <CostView quote={item.quote} />
                  <p>
                    {item.quote.source_bytes} 字节 · Embedding 估算{" "}
                    {item.quote.estimated_embedding_tokens ?? "待解析确定"}{" "}
                    Token · OCR 估算{" "}
                    {item.quote.estimated_ocr_pages ?? "待解析确定"} 页
                  </p>
                </>
              )}
            </div>
          ))}
        </div>
      )}
      {selected.length > 0 && (
        <>
          <p>
            预估按解析和索引两个任务计算，实际用量会受切片和缓存复用影响；未配置费率时仍可导入。
          </p>
          <button
            disabled={
              busy || items.some((i) => !i.quote || i.state !== "待导入")
            }
            onClick={() => void upload()}
          >
            开始导入
          </button>
          <button disabled={busy} onClick={() => void preview(selected)}>
            重新预估
          </button>
        </>
      )}
    </>
  );
}
