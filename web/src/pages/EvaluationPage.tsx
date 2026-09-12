import EvaluationReports from "../features/evaluation/EvaluationReports";
import { importCases } from "../features/evaluation/importCases";
import { useState } from "react";
import { request, type Row } from "../api";
import { ErrorNote, useData } from "../ui";

const categories: Record<string, string> = {
  DIRECT: "直接问题",
  PARAPHRASE: "同义口语",
  IDENTIFIER: "型号编号",
  UNANSWERABLE: "无答案",
  VERSION: "版本与冲突",
};
export default function EvaluationPage() {
  const sets = useData<Row[]>("/evaluation-datasets", []),
    bases = useData<Row[]>("/knowledge-bases", []),
    me = useData<Row | null>("/me", null);
  const [dataset, setDataset] = useState<Row | null>(null),
    [version, setVersion] = useState<Row | null>(null),
    [draft, setDraft] = useState<Row[]>([]),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false),
    [editing, setEditing] = useState(false);
  async function act(work: () => Promise<void>) {
    setBusy(true);
    setError("");
    try {
      await work();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  async function open(id: string) {
    const detail = await request<Row>(`/evaluation-datasets/${id}`);
    setDataset(detail);
    setVersion(null);
    setDraft([]);
    setEditing(false);
  }
  async function load(id: string) {
    const result = await request<Row>(
      `/evaluation-datasets/${dataset!.id}/versions/${id}`,
    );
    setVersion(result);
    setDraft(structuredClone(result.cases));
    setEditing(false);
  }
  function update(i: number, key: string, value: unknown) {
    setDraft((rows) =>
      rows.map((row, n) => (n === i ? { ...row, [key]: value } : row)),
    );
  }
  return (
    <div className="evaluation-page">
      <div className="page-heading">
        <div>
          <h1>测试集与评审</h1>
          <p>版本化问题、参考答案、证据与测试身份。候选标注需逐题人工核对。</p>
        </div>
      </div>
      <ErrorNote error={error || sets.error || bases.error} />
      <EvaluationReports />
      <details>
        <summary>新建测试集</summary>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            const f = new FormData(e.currentTarget);
            void act(async () => {
              const result = await request<Row>("/evaluation-datasets", {
                method: "POST",
                body: JSON.stringify({
                  name: f.get("name"),
                  knowledge_base_id: f.get("kb"),
                }),
              });
              await sets.reload();
              await open(result.id);
            });
          }}
        >
          <label>
            名称
            <input name="name" required maxLength={200} />
          </label>
          <label>
            知识库
            <select name="kb" required>
              <option value="">选择可编辑的知识库</option>
              {bases.data.map((b) => (
                <option key={b.id} value={b.id}>
                  {b.name}
                </option>
              ))}
            </select>
          </label>
          <button disabled={busy}>创建测试集</button>
        </form>
      </details>
      <div>
        {sets.data.map((s) => (
          <button
            key={s.id}
            disabled={busy}
            onClick={() => void act(() => open(s.id))}
          >
            {s.name} · {s.revision}版
          </button>
        ))}
      </div>
      {dataset && (
        <section>
          <h2>{dataset.name}</h2>
          <p>
            修订 {dataset.revision}
            ；修改保存为新版本，旧版及其评审不变。引用资料删除或授权撤销后，对应版本停止可读。
          </p>
          <div>
            {dataset.versions.map((v: Row) => (
              <button
                disabled={busy}
                key={v.id}
                onClick={() => void act(() => load(v.id))}
              >
                版本 {v.version_number}
              </button>
            ))}
          </div>
          <button disabled={busy} onClick={() => setEditing(true)}>
            编辑为新版本
          </button>
          {editing ? (
            <>
              <label>
                导入 JSON 题目数组
                <input
                  type="file"
                  accept=".json"
                  disabled={busy}
                  onChange={(e) => {
                    const file = e.target.files?.[0];
                    e.target.value = "";
                    if (file)
                      void act(async () => {
                        if (file.size > 8 * 1024 * 1024)
                          throw Error("测试集文件最多8MiB");
                        const input = JSON.parse(await file.text());
                        setDraft(importCases(input));
                      });
                  }}
                />
              </label>
              <p>
                {draft.length}
                题。新题默认使用当前成员作为测试身份；运行评估时仍需该身份的真实凭证。
              </p>
              {draft.map((c, i) => (
                <details key={i}>
                  <summary>
                    {i + 1}. {c.question || "新问题"}
                  </summary>
                  <label>
                    题目ID
                    <input
                      value={c.id}
                      onChange={(e) => update(i, "id", e.target.value)}
                    />
                  </label>
                  <label>
                    问题
                    <textarea
                      value={c.question}
                      onChange={(e) => update(i, "question", e.target.value)}
                    />
                  </label>
                  <label>
                    参考答案
                    <textarea
                      value={c.reference_answer}
                      onChange={(e) =>
                        update(i, "reference_answer", e.target.value)
                      }
                    />
                  </label>
                  <label>
                    类型
                    <select
                      value={c.category}
                      onChange={(e) => update(i, "category", e.target.value)}
                    >
                      {Object.entries(categories).map(([key, label]) => (
                        <option key={key} value={key}>
                          {label}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label>
                    可回答性
                    <select
                      value={c.answerability}
                      onChange={(e) =>
                        update(i, "answerability", e.target.value)
                      }
                    >
                      <option value="ANSWERABLE">有答案</option>
                      <option value="UNANSWERABLE">无答案，应拒答</option>
                      <option value="CONFLICT">资料存在冲突</option>
                    </select>
                  </label>
                  <label>
                    正确切片ID（逗号分隔）
                    <textarea
                      value={(c.expected_chunk_ids || []).join(",")}
                      onChange={(e) =>
                        update(
                          i,
                          "expected_chunk_ids",
                          e.target.value
                            .split(",")
                            .map((x) => x.trim())
                            .filter(Boolean),
                        )
                      }
                    />
                  </label>
                  <label>
                    测试身份类型
                    <select
                      value={c.test_subject_kind}
                      onChange={(e) =>
                        update(i, "test_subject_kind", e.target.value)
                      }
                    >
                      <option>MEMBER</option>
                      <option>APP</option>
                    </select>
                  </label>
                  <label>
                    测试成员或应用ID
                    <input
                      value={c.test_subject_id}
                      onChange={(e) =>
                        update(i, "test_subject_id", e.target.value)
                      }
                    />
                  </label>
                  <button
                    disabled={busy}
                    onClick={() =>
                      setDraft((rows) => rows.filter((_, n) => n !== i))
                    }
                  >
                    从新版本移除此题
                  </button>
                </details>
              ))}
              <button
                disabled={busy || draft.length >= 500}
                onClick={() =>
                  setDraft((rows) => [
                    ...rows,
                    {
                      id: `case-${crypto.randomUUID()}`,
                      question: "",
                      reference_answer: "",
                      category: "DIRECT",
                      answerability: "ANSWERABLE",
                      expected_chunk_ids: [],
                      test_subject_kind: "MEMBER",
                      test_subject_id: me.data?.subject_id || "",
                    },
                  ])
                }
              >
                添加问题
              </button>
              <button
                disabled={busy || !draft.length}
                onClick={() =>
                  void act(async () => {
                    const saved = await request<Row>(
                      `/evaluation-datasets/${dataset.id}/versions`,
                      {
                        method: "POST",
                        body: JSON.stringify({
                          revision: dataset.revision,
                          cases: draft,
                        }),
                      },
                    );
                    await open(dataset.id);
                    setVersion(saved);
                    setDraft(structuredClone(saved.cases));
                    await sets.reload();
                  })
                }
              >
                保存新版本
              </button>
              <button
                disabled={busy}
                onClick={() => {
                  setEditing(false);
                  setDraft(structuredClone(version?.cases || []));
                }}
              >
                放弃本次编辑
              </button>
            </>
          ) : (
            version && (
              <>
                <h3>
                  版本 {version.version_number} · {version.cases.length}题
                </h3>
                <p>
                  已评审 {version.reviews.length}题，通过{" "}
                  {
                    version.reviews.filter(
                      (r: Row) => r.decision === "APPROVED",
                    ).length
                  }
                  题。未经人工评审不计入正式质量基线。
                </p>
                {version.cases.map((c: Row) => {
                  const review = version.reviews.find(
                    (r: Row) => r.case_id === c.id,
                  );
                  return (
                    <details key={c.id}>
                      <summary>
                        {c.id} · {categories[c.category]} ·{" "}
                        {review
                          ? review.decision === "APPROVED"
                            ? "已通过"
                            : "需修订"
                          : "待人工评审"}{" "}
                        · {c.question}
                      </summary>
                      <p>参考答案：{c.reference_answer}</p>
                      <p>
                        正确证据：
                        {c.expected_chunk_ids.join(", ") || "未标注或无答案题"}
                      </p>
                      <p>
                        测试身份：{c.test_subject_kind} / {c.test_subject_id}
                      </p>
                      {review ? (
                        <p>
                          评审人 {review.reviewer_id} · {review.note}
                        </p>
                      ) : (
                        <form
                          onSubmit={(e) => {
                            e.preventDefault();
                            const f = new FormData(e.currentTarget);
                            void act(async () => {
                              const reviewed = await request<Row>(
                                `/evaluation-datasets/${dataset.id}/versions/${version.id}/reviews/${c.id}`,
                                {
                                  method: "POST",
                                  body: JSON.stringify({
                                    decision: f.get("decision"),
                                    note: f.get("note"),
                                  }),
                                },
                              );
                              setVersion(reviewed);
                            });
                          }}
                        >
                          <label>
                            评审结论
                            <select name="decision">
                              <option value="APPROVED">通过</option>
                              <option value="REJECTED">需修订</option>
                            </select>
                          </label>
                          <label>
                            评审说明
                            <textarea name="note" maxLength={2000} />
                          </label>
                          <label>
                            <input type="checkbox" required />
                            我已人工核对问题、参考答案、证据和测试身份
                          </label>
                          <button disabled={busy}>保存人工评审</button>
                        </form>
                      )}
                    </details>
                  );
                })}
              </>
            )
          )}
        </section>
      )}
    </div>
  );
}
