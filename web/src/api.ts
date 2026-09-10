export type Row = Record<string, any>;
let token = sessionStorage.getItem("careflow-token") || "";
export function setToken(value: string) {
  token = value;
  value
    ? sessionStorage.setItem("careflow-token", value)
    : sessionStorage.removeItem("careflow-token");
}
export function getToken() {
  return token;
}
export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
  ) {
    super(message);
  }
}
export async function request<T = Row>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const headers = new Headers(options.headers);
  if (token) headers.set("Authorization", `Bearer ${token}`);
  if (options.body && !(options.body instanceof FormData))
    headers.set("Content-Type", "application/json");
  if (options.method === "POST")
    headers.set("Idempotency-Key", crypto.randomUUID());
  const response = await fetch(`/api/v1${path}`, { ...options, headers });
  if (!response.ok) {
    let problem: Row = {};
    try {
      problem = await response.json();
    } catch {}
    throw new ApiError(
      response.status,
      problem.code || "CONNECTION_ERROR",
      problem.message || "服务连接失败，请检查后端状态",
    );
  }
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}
export const post = <T = Row>(path: string, body?: unknown) =>
  request<T>(path, {
    method: "POST",
    body: body === undefined ? undefined : JSON.stringify(body),
  });
export const put = <T = Row>(path: string, body: unknown) =>
  request<T>(path, { method: "PUT", body: JSON.stringify(body) });
export async function streamAnswer(
  body: unknown,
  onEvent: (name: string, data: Row) => void,
  signal: AbortSignal,
) {
  const response = await fetch("/api/v1/answers", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
      "Idempotency-Key": crypto.randomUUID(),
    },
    body: JSON.stringify(body),
    signal,
  });
  if (!response.ok) {
    const data = await response.json();
    throw new Error(data.message || "问答请求失败");
  }
  if (!response.body) throw new Error("流式连接不可用");
  const reader = response.body.getReader(),
    decoder = new TextDecoder();
  let buffer = "",
    doneSeen = false;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder
        .decode(value, { stream: true })
        .replaceAll("\r\n", "\n");
      let index;
      while ((index = buffer.indexOf("\n\n")) >= 0) {
        const raw = buffer.slice(0, index);
        buffer = buffer.slice(index + 2);
        let name = "message";
        const data: string[] = [];
        for (const line of raw.split("\n")) {
          if (line.startsWith("event:")) name = line.slice(6).trim();
          if (line.startsWith("data:")) data.push(line.slice(5).trim());
        }
        if (data.length) {
          onEvent(name, JSON.parse(data.join("\n")));
          if (name === "done" || name === "error") doneSeen = true;
        }
      }
    }
    if (!doneSeen) throw new Error("连接提前结束，回答未完成");
  } finally {
    reader.releaseLock();
  }
}

export async function downloadSource(version: string, filename: string) {
  const response = await fetch(`/api/v1/document-versions/${version}/source`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!response.ok) {
    const error = await response.json();
    throw new Error(error.message || "下载失败");
  }
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
