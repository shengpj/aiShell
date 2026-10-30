import { getBackendInfo, getBackendTicket } from "./backend";

export interface CommandHistoryEntry {
  at?: string;
  initiator?: "user" | "ai" | string;
  command: string;
  cwd?: string;
  exitCode?: number;
  status?: string;
  durationMs?: number;
  output?: string;
  message?: string;
  reason?: string;
}

export interface CommandHistoryResult {
  entries: CommandHistoryEntry[];
  truncated: boolean;
}

export async function loadCommandHistory(
  session: string,
  query = "",
  limit = 100,
): Promise<CommandHistoryResult> {
  const info = await getBackendInfo();
  if (info.status !== "ready") {
    throw new Error(info.error ?? "后端尚未就绪");
  }
  const { ticket } = await getBackendTicket("chat");
  const params = new URLSearchParams({ session, limit: String(limit) });
  if (query.trim()) {
    params.set("query", query.trim());
  }
  const response = await fetch(`${info.baseUrl}/api/commands/history?${params}`, {
    headers: { "X-AI-Shell-Ticket": ticket },
  });
  const payload = await response.json().catch(() => null);
  if (!response.ok) {
    const detail = payload?.detail ?? payload?.message ?? payload?.title;
    throw new Error(
      typeof detail === "string" && detail ? detail : `请求失败（HTTP ${response.status}）`,
    );
  }
  return payload as CommandHistoryResult;
}
