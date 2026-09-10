export function importCases(input: unknown): Record<string, any>[] {
  const rows = Array.isArray(input)
    ? input
    : (input as { cases?: unknown })?.cases;
  if (!Array.isArray(rows) || !rows.length || rows.length > 500)
    throw Error("需要1至500题的cases数组");
  const uuid =
    /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
  const ids = new Set<string>();
  for (const row of rows) {
    if (!row || typeof row !== "object" || Array.isArray(row))
      throw Error("每题必须是对象");
    for (const [key, max] of [
      ["id", 100],
      ["question", 4000],
      ["reference_answer", 8000],
    ] as const)
      if (
        typeof row[key] !== "string" ||
        !row[key].trim() ||
        row[key].length > max
      )
        throw Error(`题目字段 ${key} 不合法`);
    if (!/^[A-Za-z0-9_-]+$/.test(row.id) || ids.has(row.id))
      throw Error("题目ID不合法或重复");
    ids.add(row.id);
    if (
      ![
        "DIRECT",
        "PARAPHRASE",
        "IDENTIFIER",
        "UNANSWERABLE",
        "VERSION",
      ].includes(row.category) ||
      !["ANSWERABLE", "UNANSWERABLE", "CONFLICT"].includes(row.answerability)
    )
      throw Error("题目类型不合法");
    if (
      !["MEMBER", "APP"].includes(row.test_subject_kind) ||
      !uuid.test(row.test_subject_id)
    )
      throw Error("测试身份需要有效ID");
    if (
      !Array.isArray(row.expected_chunk_ids) ||
      row.expected_chunk_ids.length > 30 ||
      !row.expected_chunk_ids.every(
        (id: unknown) => typeof id === "string" && uuid.test(id),
      )
    )
      throw Error("正确证据需要切片ID数组");
  }
  return rows.map(
    ({
      id,
      question,
      reference_answer,
      category,
      answerability,
      expected_chunk_ids,
      test_subject_kind,
      test_subject_id,
    }) => ({
      id,
      question,
      reference_answer,
      category,
      answerability,
      expected_chunk_ids,
      test_subject_kind,
      test_subject_id,
    }),
  );
}
