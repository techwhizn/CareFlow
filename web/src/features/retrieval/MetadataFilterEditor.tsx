export type FilterDraft = { field: string; operator: string; input: string };
const fields = [
  ["title", "文档标题"], ["source", "来源"], ["language", "语言"], ["tags", "标签"],
  ["product_models", "产品型号"], ["valid_from", "生效时间"], ["valid_until", "失效时间"],
];
const operators: Record<string, string> = { eq: "等于", in: "属于任一", contains: "包含", gte: "不早于", lte: "不晚于" };
const allowed = (field: string) => field.startsWith("valid_") ? ["gte", "lte", "eq"]
  : ["tags", "product_models"].includes(field) ? ["contains", "in"] : ["eq", "in", "contains"];
export const serializeFilters = (value: FilterDraft[]) => value.map(row => ({
  field: row.field, operator: row.operator,
  value: row.operator === "in" ? row.input.split(",").map(part => part.trim()) : row.input,
}));
export default function MetadataFilterEditor({ value, onChange, disabled }: {
  value: FilterDraft[]; onChange: (value: FilterDraft[]) => void; disabled: boolean;
}) {
  const update = (index: number, next: FilterDraft) => onChange(value.map((row, i) => i === index ? next : row));
  return <fieldset disabled={disabled}>
    <legend>文档条件（全部满足）</legend>
    {value.map((row, index) => <div className="query-options" key={index}>
      <label>字段 {index + 1}<select value={row.field} onChange={event => update(index, { field: event.target.value, operator: allowed(event.target.value)[0], input: "" })}>
        {fields.map(([field, name]) => <option key={field} value={field}>{name}</option>)}
      </select></label>
      <label>条件 {index + 1}<select value={row.operator} onChange={event => update(index, { ...row, operator: event.target.value, input: "" })}>
        {allowed(row.field).map(op => <option key={op} value={op}>{operators[op]}</option>)}
      </select></label>
      <label>值 {index + 1}<input value={row.input} onChange={event => update(index, { ...row, input: event.target.value })} required maxLength={2000}
        placeholder={row.field.startsWith("valid_") ? "2026-09-10T00:00:00+08:00" : row.operator === "in" ? "多个值用英文逗号分隔" : "输入匹配值"} /></label>
      <button type="button" onClick={() => onChange(value.filter((_, i) => i !== index))}>移除条件 {index + 1}</button>
    </div>)}
    <button type="button" disabled={disabled || value.length >= 10} onClick={() => onChange([...value, { field: "language", operator: "eq", input: "" }])}>添加文档条件</button>
    <p>条件只缩小已授权、已发布且有效的资料范围。标签和型号按完整值匹配；时间需包含时区。</p>
  </fieldset>;
}
