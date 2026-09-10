import { downloadSource, post, put, request } from "../../api";

export const knowledgePaths = {
  list: "/knowledge-bases",
  documents: (id: string, page: number) => `/knowledge-bases/${id}/documents?page=${page}`,
  versions: (id: string) => `/documents/${id}/versions`,
  chunks: (id: string, page: number) => `/document-versions/${id}/chunks?page=${page}`,
};
export const knowledgeClient = {
  create: (input: { name: FormDataEntryValue | null; description: FormDataEntryValue | null }) => post(knowledgePaths.list, input),
  uploadVersion: (id: string, file: FormData) => request(knowledgePaths.versions(id), { method: "POST", body: file }),
  removeDocument: (id: string, revision: number) => request(`/documents/${id}?revision=${revision}`, { method: "DELETE" }),
  download: downloadSource,
  draft: (id: string) => post(`/document-versions/${id}/draft`),
  index: (id: string) => post(`/document-versions/${id}/index`),
  publish: (id: string, input: { version_id: string; revision: number; version_revision: number }) => post(`/documents/${id}/publications`, input),
  editChunk: (id: string, input: { content: string; enabled: boolean; revision: number; reason: string }) => put(`/chunks/${id}`, input),
};
