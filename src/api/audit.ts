import { getBackendInfo, getBackendTicket } from "./backend";

export type AuditEntry = Record<string, unknown> & { at?: string; kind?: string; initiator?: string };
export interface AuditPage { entries: AuditEntry[]; total: number; page: number; size: number; hasMore: boolean }

async function request(path: string): Promise<Response> {
  const info = await getBackendInfo();
  if (info.status !== "ready") throw new Error(info.error ?? "后端尚未就绪");
  const { ticket } = await getBackendTicket("audit");
  return fetch(`${info.baseUrl}${path}`, { headers: { "X-AI-Shell-Ticket": ticket } });
}

function encode(params: Record<string, string | number | undefined>): string {
  const query = Object.entries(params)
    .filter(([, value]) => value !== undefined && value !== "")
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
    .join("&");
  return query ? `?${query}` : "";
}

export async function searchAudit(filters: Record<string, string | number | undefined>): Promise<AuditPage> {
  const response = await request(`/api/audit${encode(filters)}`);
  const payload = await response.json().catch(() => null);
  if (!response.ok) throw new Error(payload?.detail ?? payload?.message ?? `请求失败（HTTP ${response.status}）`);
  return payload as AuditPage;
}

export async function downloadAudit(filters: Record<string, string | number | undefined>, format: "json" | "csv"): Promise<Blob> {
  const response = await request(`/api/audit/export${encode({ ...filters, format })}`);
  if (!response.ok) throw new Error(`导出失败（HTTP ${response.status}）`);
  return response.blob();
}
