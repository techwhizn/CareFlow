import { useEffect, useState } from "react";
import { post } from "../../api";
import type { Row } from "../../api";
import { ErrorNote, useData } from "../../ui";
import ImprovementRequest from "./ImprovementRequest";

export default function FeedbackPanel({
  answerId,
  kb,
}: {
  answerId: string;
  kb: string;
}) {
  const saved = useData<Row | null>(`/answers/${answerId}`, null);
  const [value, setValue] = useState("helpful"),
    [reason, setReason] = useState("WRONG_ANSWER"),
    [comment, setComment] = useState(""),
    [revision, setRevision] = useState(0),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [submitted, setSubmitted] = useState("");
  useEffect(() => {
    if (saved.data) {
      setValue(saved.data.feedback || "helpful");
      setReason(saved.data.feedback_reason || "WRONG_ANSWER");
      setComment(saved.data.feedback_comment || "");
      setRevision(saved.data.feedback_revision);
      setSubmitted(saved.data.feedback || "");
    }
  }, [saved.data]);
  return (
    <div>
      <h3>回答反馈</h3>
      <label>
        是否有帮助
        <select value={value} onChange={(e) => setValue(e.target.value)}>
          <option value="helpful">有帮助</option>
          <option value="incorrect">没有帮助 / 答案有误</option>
        </select>
      </label>
      {value === "incorrect" && (
        <label>
          错误原因
          <select value={reason} onChange={(e) => setReason(e.target.value)}>
            {[
              ["WRONG_ANSWER", "答案不正确"],
              ["WRONG_SOURCE", "引用不支持答案"],
              ["MISSING_KNOWLEDGE", "资料不完整"],
              ["OUTDATED", "资料已过时"],
              ["OTHER", "其他"],
            ].map(([id, label]) => (
              <option key={id} value={id}>
                {label}
              </option>
            ))}
          </select>
        </label>
      )}
      <label>
        补充说明
        <textarea
          value={comment}
          onChange={(e) => setComment(e.target.value)}
          maxLength={2000}
        />
      </label>
      <button
        disabled={busy || !saved.data}
        onClick={async () => {
          setBusy(true);
          setError("");
          try {
            const result = await post(`/answers/${answerId}/feedback`, {
              feedback: value,
              reason: value === "incorrect" ? reason : null,
              comment,
              revision,
            });
            setRevision(result.revision);
            setSubmitted(value);
          } catch (e) {
            setError((e as Error).message);
            void saved.reload();
          } finally {
            setBusy(false);
          }
        }}
      >
        提交反馈
      </button>
      {submitted && <p role="status">反馈已记录</p>}
      <ErrorNote error={error || saved.error} />
      {submitted === "incorrect" && (
        <ImprovementRequest
          key={answerId}
          sourceKind="ANSWER"
          sourceId={answerId}
          defaultKb={kb}
        />
      )}
    </div>
  );
}
