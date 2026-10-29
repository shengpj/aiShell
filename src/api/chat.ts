import { getBackendInfo, getBackendTicket } from "./backend";

export interface ChatHistoryText {
  id: number;
  kind: "user" | "ai" | "error";
  text: string;
  /** Groups the entries of one question and everything the agent answered in that turn. */
  turn: string;
  /** ISO timestamp of when the entry was written. */
  at?: string;
}

export interface ChatHistoryCommand {
  id: number;
  kind: "command";
  command: string;
  state: string;
  status?: string;
  exitCode?: number;
  durationMs?: number;
  timeoutSeconds?: number;
  message?: string;
  reason?: string;
  turn: string;
  at?: string;
}

export type ChatHistoryEntry = ChatHistoryText | ChatHistoryCommand;

export interface ChatHistoryResult {
  entries: ChatHistoryEntry[];
  /** True when older entries exist in the database but were not returned. */
  truncated: boolean;
}

export async function loadChatHistory(session: string, limit = 200): Promise<ChatHistoryResult> {
  const info = await getBackendInfo();
  if (info.status !== "ready") {
    throw new Error(info.error ?? "后端尚未就绪");
  }
  const { ticket } = await getBackendTicket("chat");
  const response = await fetch(
    `${info.baseUrl}/api/chat/history?session=${encodeURIComponent(session)}&limit=${limit}`,
    { headers: { "X-AI-Shell-Ticket": ticket } },
  );
  if (!response.ok) {
    throw new Error(await errorMessage(response));
  }
  const payload = (await response.json()) as {
    entries?: ChatHistoryEntry[];
    truncated?: boolean;
  };
  return { entries: payload.entries ?? [], truncated: payload.truncated === true };
}

export interface DeleteTurnResult {
  turn: string;
  messages: number;
  events: number;
}

/** Deletes one question and the agent replies of that turn, also from the model context. */
export async function deleteChatTurn(session: string, turn: string): Promise<DeleteTurnResult> {
  const info = await getBackendInfo();
  if (info.status !== "ready") {
    throw new Error(info.error ?? "后端尚未就绪");
  }
  const { ticket } = await getBackendTicket("chat");
  const response = await fetch(`${info.baseUrl}/api/chat/delete`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-AI-Shell-Ticket": ticket,
    },
    body: JSON.stringify({ session, turn }),
  });
  if (!response.ok) {
    throw new Error(await errorMessage(response));
  }
  const payload = (await response.json()) as Partial<DeleteTurnResult>;
  return { turn, messages: payload.messages ?? 0, events: payload.events ?? 0 };
}

/** Starts a new conversation for the host behind this terminal; the old one stays in the database. */
export async function resetConversation(session: string): Promise<string> {
  const info = await getBackendInfo();
  if (info.status !== "ready") {
    throw new Error(info.error ?? "后端尚未就绪");
  }
  const { ticket } = await getBackendTicket("chat");
  const response = await fetch(`${info.baseUrl}/api/chat/reset`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-AI-Shell-Ticket": ticket,
    },
    body: JSON.stringify({ session }),
  });
  if (!response.ok) {
    throw new Error(await errorMessage(response));
  }
  const payload = (await response.json()) as { conversation?: string };
  return payload.conversation ?? "";
}

async function errorMessage(response: Response): Promise<string> {
  const text = await response.text().catch(() => "");
  if (!text) {
    return `请求失败（${response.status}）`;
  }
  try {
    const body = JSON.parse(text) as { detail?: string; message?: string; error?: string };
    return body.detail ?? body.message ?? body.error ?? text;
  } catch {
    return text;
  }
}
