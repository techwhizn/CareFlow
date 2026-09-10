export default function RelevanceThreshold({ value, onChange, disabled = false }: {
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
}) {
  return (
    <label>
      最低重排分数（可选）
      <input type="number" step="any" value={value} disabled={disabled}
        onChange={(event) => onChange(event.target.value)} placeholder="留空不过滤" />
      <small>仅保留达到此分数的证据；分数范围取决于重排模型。无法评分时不返回证据。</small>
    </label>
  );
}
