export function sourceLabels(
  location: Record<string, unknown>,
  manual: boolean,
): string[] {
  if (manual || location.type === "manual")
    return ["人工补充或修订，无原文件定位"];
  if (location.type === "manual_split" || location.type === "manual_merge")
    return ["人工拆分或合并，展示原始来源范围"];
  const labels: string[] = [];
  const positive = (value: unknown): value is number =>
    typeof value === "number" && Number.isInteger(value) && value > 0;
  if (Array.isArray(location.title_path))
    labels.push(
      location.title_path
        .filter((value) => typeof value === "string")
        .join(" › "),
    );
  if (positive(location.page)) labels.push(`第 ${location.page} 页`);
  if (positive(location.paragraph)) labels.push(`第 ${location.paragraph} 段`);
  if (typeof location.table_name === "string")
    labels.push(`表格：${location.table_name}`);
  else if (typeof location.sheet === "string")
    labels.push(`工作表：${location.sheet}`);
  else if (positive(location.table)) labels.push(`表格 ${location.table}`);
  if (positive(location.row)) labels.push(`第 ${location.row} 行`);
  if (typeof location.cell_range === "string")
    labels.push(`单元格 ${location.cell_range}`);
  if (positive(location.column_start) && positive(location.column_end))
    labels.push(`列 ${location.column_start}–${location.column_end}`);
  if (
    location.type === "text" &&
    typeof location.start === "number" &&
    typeof location.block_start === "number" &&
    typeof location.block_end === "number"
  )
    labels.push(
      `原文字符区间 [${location.start + location.block_start}, ${location.start + location.block_end})`,
    );
  return labels.filter(Boolean);
}
