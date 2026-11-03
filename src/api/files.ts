import { getBackendInfo, getBackendTicket } from "./backend";

export type FileType = "dir" | "file" | "link";
export type PreviewKind = "text" | "image" | "binary" | "too-large";

export interface FileEntry {
  name: string;
  path: string;
  type: FileType;
  size: number;
  modifiedAt: number;
  permissions: string;
  owner: string;
  group: string;
}

export interface FileListing {
  path: string;
  entries: FileEntry[];
  truncated: boolean;
}

export interface FilePreview {
  path: string;
  kind: PreviewKind;
  size: number;
  modifiedAt: number;
  mime: string | null;
  text: string | null;
  data: string | null;
  truncated: boolean;
}

async function request<T>(path: string): Promise<T> {
  const info = await getBackendInfo();
  if (info.status !== "ready") {
    throw new Error(info.error ?? "后端尚未就绪");
  }
  const { ticket } = await getBackendTicket("files");
  const response = await fetch(`${info.baseUrl}${path}`, {
    headers: { "X-AI-Shell-Ticket": ticket },
  });
  const payload = await response.json().catch(() => null);
  if (!response.ok) {
    const detail = payload?.detail ?? payload?.message ?? payload?.title;
    throw new Error(
      typeof detail === "string" && detail ? detail : `请求失败（HTTP ${response.status}）`,
    );
  }
  return payload as T;
}

async function requestJson<T>(path: string, method: string, body: unknown): Promise<T> {
  const info = await getBackendInfo();
  if (info.status !== "ready") {
    throw new Error(info.error ?? "后端尚未就绪");
  }
  const { ticket } = await getBackendTicket("files");
  const response = await fetch(`${info.baseUrl}${path}`, {
    method,
    headers: {
      "Content-Type": "application/json",
      "X-AI-Shell-Ticket": ticket,
    },
    body: JSON.stringify(body),
  });
  const payload = await response.json().catch(() => null);
  if (!response.ok) {
    const detail = payload?.detail ?? payload?.message ?? payload?.title;
    throw new Error(
      typeof detail === "string" && detail ? detail : `请求失败（HTTP ${response.status}）`,
    );
  }
  return payload as T;
}

export function listFiles(session: string, path: string): Promise<FileListing> {
  return request(
    `/api/files?session=${encodeURIComponent(session)}&path=${encodeURIComponent(path)}`,
  );
}

export function previewFile(session: string, path: string): Promise<FilePreview> {
  return request(
    `/api/files/preview?session=${encodeURIComponent(session)}&path=${encodeURIComponent(path)}`,
  );
}

export interface FileWriteResult {
  path: string;
  size: number;
  modifiedAt: number;
}

export function writeTextFile(
  session: string,
  path: string,
  content: string,
  expectedModifiedAt: number,
): Promise<FileWriteResult> {
  return requestJson<FileWriteResult>("/api/files/content", "PUT", {
    session,
    path,
    content,
    expectedModifiedAt,
  });
}

export interface FileRenameResult { from: string; to: string; type: FileType }
export interface FileDeleteResult { path: string; type: FileType }
export interface FileUploadResult { path: string; name: string; size: number; modifiedAt: number }

export async function uploadFile(session: string, directory: string, file: File): Promise<FileUploadResult> {
  const info = await getBackendInfo();
  if (info.status !== "ready") throw new Error(info.error ?? "后端尚未就绪");
  const { ticket } = await getBackendTicket("files");
  const form = new FormData();
  form.set("session", session);
  form.set("directory", directory);
  form.set("file", file, file.name);
  const response = await fetch(`${info.baseUrl}/api/files/upload`, {
    method: "POST",
    headers: { "X-AI-Shell-Ticket": ticket },
    body: form,
  });
  const payload = await response.json().catch(() => null);
  if (!response.ok) {
    const detail = payload?.detail ?? payload?.message ?? payload?.title;
    throw new Error(typeof detail === "string" && detail ? detail : `请求失败（HTTP ${response.status}）`);
  }
  return payload as FileUploadResult;
}

export function renameFile(session: string, from: string, to: string): Promise<FileRenameResult> {
  return requestJson<FileRenameResult>("/api/files/rename", "POST", { session, from, to });
}

export async function deleteFile(session: string, path: string): Promise<FileDeleteResult> {
  const info = await getBackendInfo();
  if (info.status !== "ready") throw new Error(info.error ?? "后端尚未就绪");
  const { ticket } = await getBackendTicket("files");
  const response = await fetch(
    `${info.baseUrl}/api/files/entry?session=${encodeURIComponent(session)}&path=${encodeURIComponent(path)}`,
    { method: "DELETE", headers: { "X-AI-Shell-Ticket": ticket } },
  );
  const payload = await response.json().catch(() => null);
  if (!response.ok) throw new Error(payload?.detail ?? payload?.message ?? `请求失败（HTTP ${response.status}）`);
  return payload as FileDeleteResult;
}

export async function downloadFile(session: string, path: string): Promise<Blob> {
  const info = await getBackendInfo();
  if (info.status !== "ready") {
    throw new Error(info.error ?? "后端尚未就绪");
  }
  const { ticket } = await getBackendTicket("files");
  const response = await fetch(
    `${info.baseUrl}/api/files/download?session=${encodeURIComponent(session)}&path=${encodeURIComponent(path)}`,
    { headers: { "X-AI-Shell-Ticket": ticket } },
  );
  if (!response.ok) {
    const payload = await response.json().catch(() => null);
    const detail = payload?.detail ?? payload?.message ?? payload?.title;
    throw new Error(
      typeof detail === "string" && detail ? detail : `请求失败（HTTP ${response.status}）`,
    );
  }
  return response.blob();
}
