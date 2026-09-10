import { useState } from "react";
import type { Row } from "../../api";
import { post } from "../../api";
import { Dialog, ErrorNote } from "../../ui";

export default function ChunkTools({
  version,
  rows,
  selected,
  saved,
}: {
  version: Row;
  rows: Row[];
  selected: Row | null;
  saved: () => Promise<void>;
}) {
  const [open, setOpen] = useState(false),
    [operation, setOperation] = useState("SET_ENABLED"),
    [ids, setIds] = useState<string[]>([]);
  const [offset, setOffset] = useState(0),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const chosen = rows.filter((row) => ids.includes(row.id));
  return (
    <>
      <button
        disabled={
          version.ever_published ||
          !["PARSED", "READY", "FAILED"].includes(version.state)
        }
        onClick={() => {
          setIds(selected ? [selected.id] : []);
          setError("");
          setOffset(0);
          setOpen(true);
        }}
      >
        拆分、合并与批量管理
      </button>
      {open && (
        <Dialog title="管理草稿切片" close={() => setOpen(false)}>
          <ErrorNote error={error} />
          <form
            onSubmit={async (event) => {
              event.preventDefault();
              const form = new FormData(event.currentTarget);
              setBusy(true);
              setError("");
              try {
                await post(
                  `/document-versions/${version.id}/chunk-operations`,
                  {
                    revision: version.revision,
                    action: operation,
                    chunks: chosen.map((row) => ({
                      id: row.id,
                      revision: row.revision,
                    })),
                    split_offsets: operation === "SPLIT" ? [offset] : null,
                    enabled:
                      operation === "SET_ENABLED"
                        ? form.get("enabled") === "true"
                        : null,
                    tags:
                      operation === "TAGS"
                        ? String(form.get("tags"))
                            .split("\n")
                            .map((value) => value.trim())
                            .filter(Boolean)
                        : null,
                    reason: String(form.get("reason")),
                  },
                );
                await saved();
                setOpen(false);
              } catch (cause) {
                setError((cause as Error).message);
              } finally {
                setBusy(false);
              }
            }}
          >
            <label>
              操作
              <select
                value={operation}
                onChange={(event) => {
                  setOffset(0);
                  setOperation(event.target.value);
                }}
              >
                <option value="SET_ENABLED">启用或禁用</option>
                <option value="TAGS">替换标签</option>
                <option value="SPLIT">拆分一个切片</option>
                <option value="MERGE">合并相邻切片</option>
              </select>
            </label>
            <fieldset>
              <legend>选择本页切片（{ids.length} 个）</legend>
              {rows.map((row) => (
                <label key={row.id}>
                  <input
                    type="checkbox"
                    checked={ids.includes(row.id)}
                    onChange={(event) => {
                      setOffset(0);
                      setIds(
                        event.target.checked
                          ? [...ids, row.id]
                          : ids.filter((id) => id !== row.id),
                      );
                    }}
                  />
                  切片 {Number(row.ordinal_no) + 1} ·{" "}
                  {String(row.content).slice(0, 65)}
                </label>
              ))}
            </fieldset>
            {operation === "SET_ENABLED" && (
              <label>
                状态
                <select name="enabled">
                  <option value="false">禁用</option>
                  <option value="true">启用</option>
                </select>
              </label>
            )}
            {operation === "TAGS" && (
              <label>
                标签（每行一个，最多20个；留空清除）
                <textarea name="tags" rows={4} />
              </label>
            )}
            {operation === "SPLIT" && chosen.length === 1 && (
              <label>
                在内容中放置光标，作为拆分位置
                <textarea
                  readOnly
                  value={chosen[0].content}
                  rows={8}
                  onSelect={(event) =>
                    setOffset(event.currentTarget.selectionStart)
                  }
                />
                <span className="muted">
                  保存保留两侧全部文本，不能在开头或结尾拆分。
                </span>
              </label>
            )}
            <p className="muted">
              拆分需选1个，合并需选2–20个相邻且启用状态相同的切片；分组内容需先解除关联。原始来源和修改记录保留，索引会失效。
            </p>
            <label>
              修改原因
              <input name="reason" required maxLength={1000} />
            </label>
            <button
              className="primary"
              disabled={
                busy ||
                !ids.length ||
                (operation === "SPLIT" &&
                  (chosen.length !== 1 ||
                    offset <= 0 ||
                    offset >= String(chosen[0]?.content ?? "").length)) ||
                (operation === "MERGE" && (ids.length < 2 || ids.length > 20))
              }
            >
              保存操作
            </button>
          </form>
        </Dialog>
      )}
    </>
  );
}
