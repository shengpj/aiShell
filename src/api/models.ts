import { getBackendInfo, getBackendTicket } from "./backend";

export interface ModelView {
  id: string;
  name: string;
  baseUrl: string;
  model: string;
  /** The API key itself is never sent to the webview. */
  hasApiKey: boolean;
}

export interface ModelInput {
  name: string;
  baseUrl: string;
  model: string;
  /** Omitted or blank keeps the stored key when editing. */
  apiKey?: string;
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const info = await getBackendInfo();
  if (info.status !== "ready") {
    throw new Error(info.error ?? "后端尚未就绪");
  }
  const { ticket } = await getBackendTicket("models");
  const response = await fetch(`${info.baseUrl}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      "X-AI-Shell-Ticket": ticket,
      ...(init.headers ?? {}),
    },
  });
  if (response.status === 204) {
    return undefined as T;
  }
  const payload = await response.json().catch(() => null);
  if (!response.ok) {
    const detail = payload?.detail ?? payload?.message ?? payload?.title;
    throw new Error(
      typeof detail === "string" && detail ? detail : `请求失败（HTTP ${response.status}）`,
    );
  }
  return payload as T;
}

export function listModels(): Promise<ModelView[]> {
  return request<ModelView[]>("/api/models");
}

export function createModel(input: ModelInput): Promise<ModelView> {
  return request<ModelView>("/api/models", { method: "POST", body: JSON.stringify(input) });
}

export function updateModel(id: string, input: ModelInput): Promise<ModelView> {
  return request<ModelView>(`/api/models/${id}`, {
    method: "PUT",
    body: JSON.stringify(input),
  });
}

export function deleteModel(id: string): Promise<void> {
  return request<void>(`/api/models/${id}`, { method: "DELETE" });
}
