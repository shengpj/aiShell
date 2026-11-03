import { getBackendInfo, getBackendTicket } from "./backend";

export interface CustomRunbookField { id: string; label: string; placeholder: string }
export interface CustomRunbook {
  id: string; title: string; summary: string; category: string;
  fields: CustomRunbookField[]; steps: string[]; success: string[];
  version: number; createdAt: string; updatedAt: string;
}
export interface CustomRunbookInput {
  title: string; summary: string; category: string; fields: CustomRunbookField[];
  steps: string[]; success: string[]; version?: number;
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const info = await getBackendInfo();
  if (info.status !== "ready") throw new Error(info.error ?? "后端尚未就绪");
  const { ticket } = await getBackendTicket("runbooks");
  const response = await fetch(`${info.baseUrl}${path}`, {
    ...init,
    headers: { "Content-Type": "application/json", "X-AI-Shell-Ticket": ticket, ...(init.headers ?? {}) },
  });
  if (response.status === 204) return undefined as T;
  const payload = await response.json().catch(() => null);
  if (!response.ok) throw new Error(payload?.detail ?? payload?.message ?? `请求失败（HTTP ${response.status}）`);
  return payload as T;
}

export function listCustomRunbooks(): Promise<CustomRunbook[]> { return request("/api/runbooks"); }
export function createCustomRunbook(input: CustomRunbookInput): Promise<CustomRunbook> {
  return request("/api/runbooks", { method: "POST", body: JSON.stringify(input) });
}
export function updateCustomRunbook(id: string, input: CustomRunbookInput): Promise<CustomRunbook> {
  return request(`/api/runbooks/${encodeURIComponent(id)}`, { method: "PUT", body: JSON.stringify(input) });
}
export function deleteCustomRunbook(id: string): Promise<void> {
  return request(`/api/runbooks/${encodeURIComponent(id)}`, { method: "DELETE" });
}
