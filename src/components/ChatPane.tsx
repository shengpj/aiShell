import { confirm as confirmDialog } from "@tauri-apps/plugin-dialog";
import { useEffect, useRef, useState, type KeyboardEvent } from "react";
import { getBackendInfo, getBackendTicket, isTauri } from "../api/backend";
import {
  loadChatHistory,
  resetConversation,
  type ChatHistoryEntry,
  type ChatHistoryResult,
} from "../api/chat";

async function confirmNewConversation(): Promise<boolean> {
  if (!isTauri()) {
    return window.confirm("开始新对话？当前对话会从界面清空。");
  }
  try {
    return await confirmDialog("开始新对话？当前对话会从界面清空，历史仍保留在数据库中。", {
      title: "新对话",
      kind: "info",
      okLabel: "新对话",
      cancelLabel: "取消",
    });
  } catch (cause) {
    console.error("confirmation dialog failed", cause);
    return false;
  }
}

interface ChatPaneProps {
  sessionId: string | null;
}

type CommandState = "started" | "finished" | "refused";

interface CommandEvent {
  state: CommandState;
  command: string;
  status?: string;
  exitCode?: number;
  durationMs?: number;
  reason?: string;
  message?: string;
  timeoutSeconds?: number;
}

type Entry =
  | { id: number; kind: "user"; text: string }
  | { id: number; kind: "ai"; text: string }
  | { id: number; kind: "error"; text: string }
  | {
      id: number;
      kind: "command";
      command: string;
      state: CommandState;
      status?: string;
      exitCode?: number;
      durationMs?: number;
      note?: string;
      timeoutSeconds?: number;
    };

export default function ChatPane({ sessionId }: ChatPaneProps) {
  const [entries, setEntries] = useState<Entry[]>([]);
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const bodyRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<AbortController | null>(null);
  const nextId = useRef(1);

  useEffect(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    setEntries([]);
    setNotice(null);
    setBusy(false);
    if (!sessionId) {
      setHistoryLoading(false);
      return;
    }
    let cancelled = false;
    setHistoryLoading(true);
    void (async () => {
      try {
        let history: ChatHistoryResult | null = null;
        let lastError: unknown = null;
        for (let attempt = 0; attempt < 3 && !cancelled; attempt += 1) {
          try {
            history = await loadChatHistory(sessionId);
            break;
          } catch (cause) {
            lastError = cause;
            if (attempt < 2) {
              await new Promise((resolve) => window.setTimeout(resolve, 800));
            }
          }
        }
        if (cancelled) {
          return;
        }
        if (!history) {
          throw lastError ?? new Error("历史对话加载失败");
        }
        const restored = history.entries.map(toEntry);
        setEntries(restored);
        nextId.current = restored.reduce((max, entry) => Math.max(max, entry.id), 0) + 1;
        if (history.truncated) {
          setNotice(
            `历史较长，仅显示最近 ${restored.length} 条；更早的记录仍保留在数据库中。`,
          );
        }
      } catch (cause) {
        if (!cancelled) {
          const text = cause instanceof Error ? cause.message : String(cause);
          setNotice(`历史对话加载失败：${text}`);
        }
      } finally {
        if (!cancelled) {
          setHistoryLoading(false);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [sessionId]);

  useEffect(() => {
    const body = bodyRef.current;
    if (body) {
      body.scrollTop = body.scrollHeight;
    }
  }, [entries]);

  const appendAiText = (text: string) => {
    setEntries((current) => {
      const last = current[current.length - 1];
      if (last?.kind === "ai") {
        return [...current.slice(0, -1), { ...last, text: last.text + text }];
      }
      return [...current, { id: nextId.current++, kind: "ai", text }];
    });
  };

  const applyCommand = (event: CommandEvent) => {
    if (!event.command) {
      return;
    }
    if (event.state === "started") {
      setEntries((current) => [
        ...current,
        {
          id: nextId.current++,
          kind: "command",
          command: event.command,
          state: "started",
          timeoutSeconds: event.timeoutSeconds,
        },
      ]);
      return;
    }
    if (event.state === "refused") {
      setEntries((current) => [
        ...current,
        {
          id: nextId.current++,
          kind: "command",
          command: event.command,
          state: "refused",
          note: event.reason,
        },
      ]);
      return;
    }
    setEntries((current) => {
      for (let index = current.length - 1; index >= 0; index -= 1) {
        const entry = current[index];
        if (
          entry.kind === "command" &&
          entry.state === "started" &&
          entry.command === event.command
        ) {
          const next = [...current];
          next[index] = {
            ...entry,
            state: "finished",
            status: event.status,
            exitCode: event.exitCode,
            durationMs: event.durationMs,
            note: event.message,
          };
          return next;
        }
      }
      return [
        ...current,
        {
          id: nextId.current++,
          kind: "command",
          command: event.command,
          state: "finished",
          status: event.status,
          exitCode: event.exitCode,
          durationMs: event.durationMs,
          note: event.message,
        },
      ];
    });
  };

  const applyEvent = (event: string, data: string) => {
    let payload: Record<string, unknown>;
    try {
      payload = JSON.parse(data) as Record<string, unknown>;
    } catch {
      return;
    }
    if (event === "text" && typeof payload.text === "string") {
      appendAiText(payload.text);
    } else if (event === "command") {
      applyCommand(payload as unknown as CommandEvent);
    } else if (event === "error" && typeof payload.message === "string") {
      setEntries((current) => [
        ...current,
        { id: nextId.current++, kind: "error", text: payload.message as string },
      ]);
    } else if (event === "done") {
      setBusy(false);
    }
  };

  const send = async () => {
    const message = draft.trim();
    if (!message || busy || !sessionId) {
      return;
    }
    setDraft("");
    setNotice(null);
    setBusy(true);
    setEntries((current) => [
      ...current,
      { id: nextId.current++, kind: "user", text: message },
    ]);
    const controller = new AbortController();
    abortRef.current = controller;
    try {
      const info = await getBackendInfo();
      if (info.status !== "ready") {
        throw new Error(info.error ?? "后端尚未就绪");
      }
      const { ticket } = await getBackendTicket("chat");
      const response = await fetch(`${info.baseUrl}/api/chat`, {
        method: "POST",
        signal: controller.signal,
        headers: {
          "Content-Type": "application/json",
          "X-AI-Shell-Ticket": ticket,
        },
        body: JSON.stringify({ session: sessionId, message }),
      });
      if (!response.ok || !response.body) {
        throw new Error(await errorMessage(response));
      }
      await readSse(response.body, applyEvent);
    } catch (cause) {
      if (!controller.signal.aborted) {
        const text = cause instanceof Error ? cause.message : String(cause);
        setEntries((current) => [
          ...current,
          { id: nextId.current++, kind: "error", text },
        ]);
      }
    } finally {
      if (abortRef.current === controller) {
        abortRef.current = null;
      }
      setBusy(false);
    }
  };

  const stop = async () => {
    setNotice("正在请求停止…");
    try {
      const info = await getBackendInfo();
      if (info.status !== "ready") {
        throw new Error(info.error ?? "后端尚未就绪");
      }
      const { ticket } = await getBackendTicket("chat");
      const response = await fetch(`${info.baseUrl}/api/agent/stop`, {
        method: "POST",
        headers: { "X-AI-Shell-Ticket": ticket },
      });
      if (!response.ok) {
        throw new Error(await errorMessage(response));
      }
      setNotice("已请求停止当前 AI 命令");
    } catch (cause) {
      setNotice(cause instanceof Error ? cause.message : String(cause));
    }
  };

  const startNewConversation = async () => {
    if (!sessionId || busy) {
      return;
    }
    if (entries.length > 0 && !(await confirmNewConversation())) {
      return;
    }
    setNotice("正在开始新对话…");
    try {
      const conversation = await resetConversation(sessionId);
      setEntries([]);
      nextId.current = 1;
      setNotice(
        conversation ? `已开始新对话（${conversation}），旧对话仍保留在数据库中` : "已开始新对话",
      );
    } catch (cause) {
      setNotice(cause instanceof Error ? cause.message : String(cause));
    }
  };

  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      void send();
    }
  };

  return (
    <aside className="pane chat">
      <div className="pane-header">
        <span>AI 助手</span>
        <div className="chat-header-actions">
          <span className={sessionId ? "chat-session chat-session-on" : "chat-session"}>
            {sessionId ? "终端已连接" : "终端未连接"}
          </span>
          <button
            className="mini"
            type="button"
            title="开始新对话（当前对话从界面清空，历史仍保留在数据库中）"
            onClick={() => void startNewConversation()}
            disabled={!sessionId || busy || historyLoading}
          >
            新对话
          </button>
        </div>
      </div>
      <div className="chat-body" ref={bodyRef}>
        {entries.length === 0 && (
          <div className="message message-ai">
            {historyLoading
              ? "正在加载历史对话…"
              : "连接终端后，可以让我检查状态或执行运维命令。命令会自动执行，危险命令会被拦截。"}
          </div>
        )}
        {entries.map((entry) => renderEntry(entry))}
        {busy && <div className="chat-thinking">AI 正在处理…</div>}
      </div>
      {notice && <div className="terminal-message">{notice}</div>}
      <div className="chat-input">
        <textarea
          rows={2}
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={onKeyDown}
          placeholder={
            sessionId
              ? "描述你要做的操作…（Enter 发送，Shift+Enter 换行）"
              : "请先在终端连接主机"
          }
          aria-label="发送给 AI 的消息"
          disabled={!sessionId || busy || historyLoading}
        />
        {busy ? (
          <button className="ghost-button stop-button" type="button" onClick={() => void stop()}>
            停止
          </button>
        ) : (
          <button
            className="send-button"
            type="button"
            onClick={() => void send()}
            disabled={!sessionId || historyLoading || !draft.trim()}
          >
            发送
          </button>
        )}
      </div>
    </aside>
  );
}

function toEntry(entry: ChatHistoryEntry): Entry {
  if (entry.kind === "command") {
    return {
      id: entry.id,
      kind: "command",
      command: entry.command,
      state: entry.state as CommandState,
      status: entry.status,
      exitCode: entry.exitCode,
      durationMs: entry.durationMs,
      note: entry.message ?? entry.reason,
      timeoutSeconds: entry.timeoutSeconds,
    };
  }
  return { id: entry.id, kind: entry.kind, text: entry.text };
}

function renderEntry(entry: Entry) {
  switch (entry.kind) {
    case "user":
      return (
        <div key={entry.id} className="message message-user">
          {entry.text}
        </div>
      );
    case "ai":
      return (
        <div key={entry.id} className="message message-ai">
          {entry.text}
        </div>
      );
    case "error":
      return (
        <div key={entry.id} className="message message-error">
          {entry.text}
        </div>
      );
    case "command":
      return <CommandCard key={entry.id} entry={entry} />;
  }
}

function CommandCard({ entry }: { entry: Extract<Entry, { kind: "command" }> }) {
  const meta = commandMeta(entry);
  return (
    <div className={`command command-${entry.state}`}>
      <div className="command-line">{entry.command}</div>
      <div className={meta.bad ? "command-meta command-meta-bad" : "command-meta"}>
        {meta.text}
      </div>
    </div>
  );
}

function commandMeta(entry: Extract<Entry, { kind: "command" }>): {
  text: string;
  bad: boolean;
} {
  if (entry.state === "started") {
    const timeout = entry.timeoutSeconds ? ` · 超时 ${entry.timeoutSeconds}s` : "";
    return { text: `执行中${timeout}…`, bad: false };
  }
  if (entry.state === "refused") {
    return { text: `已拒绝：${entry.note ?? "命令被拦截"}`, bad: true };
  }
  const parts: string[] = [];
  if (entry.exitCode !== undefined && entry.exitCode >= 0) {
    parts.push(`退出码 ${entry.exitCode}`);
  }
  if (entry.status && entry.status !== "completed") {
    parts.push(entry.status);
  }
  if (entry.durationMs !== undefined) {
    parts.push(`${entry.durationMs} ms`);
  }
  if (entry.note) {
    parts.push(entry.note);
  }
  const bad =
    (entry.status !== undefined && entry.status !== "completed") ||
    (entry.exitCode ?? 0) !== 0;
  return { text: parts.join(" · ") || "完成", bad };
}

async function readSse(
  body: ReadableStream<Uint8Array>,
  onEvent: (event: string, data: string) => void,
) {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      buffer += decoder.decode(value, { stream: true });
      let boundary = buffer.indexOf("\n\n");
      while (boundary >= 0) {
        dispatchFrame(buffer.slice(0, boundary), onEvent);
        buffer = buffer.slice(boundary + 2);
        boundary = buffer.indexOf("\n\n");
      }
    }
    if (buffer.trim()) {
      dispatchFrame(buffer, onEvent);
    }
  } finally {
    reader.releaseLock();
  }
}

function dispatchFrame(frame: string, onEvent: (event: string, data: string) => void) {
  let event = "message";
  const data: string[] = [];
  for (const rawLine of frame.split("\n")) {
    const line = rawLine.replace(/\r$/, "");
    if (line.startsWith("event:")) {
      event = line.slice(6).trim();
    } else if (line.startsWith("data:")) {
      data.push(line.slice(5).trimStart());
    }
  }
  if (data.length > 0) {
    onEvent(event, data.join("\n"));
  }
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
