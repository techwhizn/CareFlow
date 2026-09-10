import { consumeAnswerStream } from "./answerStream";
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
  await consumeAnswerStream(response.body, onEvent, signal);
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
