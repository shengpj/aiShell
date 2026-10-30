import { confirm as confirmDialog } from "@tauri-apps/plugin-dialog";
import { useCallback, useEffect, useRef, useState, type KeyboardEvent } from "react";
import { getBackendInfo, getBackendTicket, isTauri } from "../api/backend";
import {
  decideChatCommand,
  deleteChatTurn,
  loadChatHistory,
  resetConversation,
  type ChatHistoryEntry,
  type ChatHistoryResult,
} from "../api/chat";
import { listModels, type ModelView } from "../api/models";
import ModelPicker from "./ModelPicker";
import ModelSettings from "./ModelSettings";

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
  /** Models can only be listed once the sidecar answers. */
  backendReady: boolean;
  prefill?: string | null;
  onPrefillConsumed?: () => void;
}

type CommandState = "pending" | "started" | "finished" | "refused";

type ExecutionPolicy = "auto" | "confirm" | "explain";

const EXECUTION_POLICY_KEY = "aishell.execution-policy";

const executionPolicies: Array<{ value: ExecutionPolicy; label: string; title: string }> = [
  { value: "auto", label: "自动", title: "AI 命令自动执行" },
  { value: "confirm", label: "确认", title: "每条 AI 命令执行前确认" },
  { value: "explain", label: "仅解释", title: "AI 只分析和解释，不执行命令" },
];

interface CommandEvent {
  state: CommandState;
  command: string;
  status?: string;
  exitCode?: number;
  durationMs?: number;
  output?: string;
  reason?: string;
  message?: string;
  timeoutSeconds?: number;
  approvalToken?: string;
}

type Entry =
  | { id: number; kind: "user"; text: string; turn: string; at?: string }
  | { id: number; kind: "ai"; text: string; turn: string; at?: string }
  | { id: number; kind: "error"; text: string; turn: string; at?: string }
  | {
      id: number;
      kind: "command";
      command: string;
      state: CommandState;
      status?: string;
      exitCode?: number;
      durationMs?: number;
      output?: string;
      note?: string;
      timeoutSeconds?: number;
      approvalToken?: string;
      turn: string;
      at?: string;
    };

type UserEntry = Extract<Entry, { kind: "user" }>;

interface TurnGroup {
  turn: string;
  user: UserEntry | null;
  items: Entry[];
}

export default function ChatPane({
  sessionId,
  backendReady,
  prefill = null,
  onPrefillConsumed,
}: ChatPaneProps) {
  const [entries, setEntries] = useState<Entry[]>([]);
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [selectedTurn, setSelectedTurn] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [copiedTurn, setCopiedTurn] = useState<string | null>(null);
  const [models, setModels] = useState<ModelView[]>([]);
  const [modelId, setModelId] = useState("");
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [executionPolicy, setExecutionPolicy] = useState<ExecutionPolicy>(() => {
    try {
      const value = window.localStorage.getItem(EXECUTION_POLICY_KEY);
      return value === "confirm" || value === "explain" ? value : "auto";
    } catch {
      return "auto";
    }
  });
  const [pendingAction, setPendingAction] = useState<string | null>(null);
  const bodyRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<AbortController | null>(null);
  const nextId = useRef(1);
  const currentTurn = useRef<string | null>(null);
  const copyTimer = useRef<number | null>(null);

  useEffect(() => {
    if (!prefill) {
      return;
    }
    setDraft(prefill);
    onPrefillConsumed?.();
  }, [onPrefillConsumed, prefill]);

  const loadModels = useCallback(async () => {
    try {
      const list = await listModels();
      setModels(list);
      setModelId((current) =>
        list.some((model) => model.id === current) ? current : (list[0]?.id ?? ""),
      );
    } catch (cause) {
      setNotice(`模型列表加载失败：${cause instanceof Error ? cause.message : String(cause)}`);
    }
  }, []);

  useEffect(() => {
    if (backendReady) {
      void loadModels();
    }
  }, [backendReady, loadModels]);

  useEffect(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    currentTurn.current = null;
    setEntries([]);
    setNotice(null);
    setBusy(false);
    setSelectedTurn(null);
    setCopiedTurn(null);
    if (copyTimer.current !== null) {
      window.clearTimeout(copyTimer.current);
      copyTimer.current = null;
    }
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
    const turn = currentTurn.current ?? "";
    setEntries((current) => {
      const last = current[current.length - 1];
      if (last?.kind === "ai" && last.turn === turn) {
        return [...current.slice(0, -1), { ...last, text: last.text + text }];
      }
      return [...current, { id: nextId.current++, kind: "ai", text, turn }];
    });
  };

  const applyCommand = (event: CommandEvent) => {
    if (!event.command) {
      return;
    }
    const turn = currentTurn.current ?? "";
    if (event.state === "pending") {
      setEntries((current) => [
        ...current,
        {
          id: nextId.current++,
          kind: "command",
          command: event.command,
          state: "pending",
          approvalToken: event.approvalToken,
          timeoutSeconds: event.timeoutSeconds,
          turn,
        },
      ]);
      return;
    }
    if (event.state === "started") {
      setEntries((current) => {
        const next = [...current];
        for (let index = next.length - 1; index >= 0; index -= 1) {
          const entry = next[index];
          if (
            entry.kind === "command" &&
            entry.state === "pending" &&
            entry.approvalToken === event.approvalToken
          ) {
            next[index] = {
              ...entry,
              state: "started",
              timeoutSeconds: event.timeoutSeconds,
            };
            return next;
          }
        }
        return [
          ...next,
          {
            id: nextId.current++,
            kind: "command",
            command: event.command,
            state: "started",
            approvalToken: event.approvalToken,
            timeoutSeconds: event.timeoutSeconds,
            turn,
          },
        ];
      });
      return;
    }
    if (event.state === "refused") {
      setEntries((current) => {
        const next = [...current];
        for (let index = next.length - 1; index >= 0; index -= 1) {
          const entry = next[index];
          if (
            entry.kind === "command" &&
            entry.state === "pending" &&
            entry.approvalToken === event.approvalToken
          ) {
            next[index] = { ...entry, state: "refused", note: event.reason };
            return next;
          }
        }
        return [
          ...next,
          {
            id: nextId.current++,
            kind: "command",
            command: event.command,
            state: "refused",
            note: event.reason,
            approvalToken: event.approvalToken,
            turn,
          },
        ];
      });
      return;
    }
    setEntries((current) => {
      for (let index = current.length - 1; index >= 0; index -= 1) {
        const entry = current[index];
        if (
          entry.kind === "command" &&
          entry.state === "started" &&
          (entry.command === event.command ||
            (event.approvalToken !== undefined && entry.approvalToken === event.approvalToken))
        ) {
          const next = [...current];
          next[index] = {
            ...entry,
            state: "finished",
            status: event.status,
            exitCode: event.exitCode,
            durationMs: event.durationMs,
            note: event.message,
            output: event.output,
            approvalToken: event.approvalToken ?? entry.approvalToken,
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
          output: event.output,
          turn,
        },
      ];
    });
  };

  const changeExecutionPolicy = (value: ExecutionPolicy) => {
    setExecutionPolicy(value);
    try {
      window.localStorage.setItem(EXECUTION_POLICY_KEY, value);
    } catch {
      // Preferences are still active for this session when storage is unavailable.
    }
  };

  const decideCommand = async (token: string, approved: boolean) => {
    if (pendingAction) return;
    setPendingAction(token);
    setNotice(approved ? "正在批准命令…" : "正在拒绝命令…");
    try {
      await decideChatCommand(token, approved);
      setNotice(approved ? "命令已批准" : "命令已拒绝");
    } catch (cause) {
      setNotice(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setPendingAction(null);
    }
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
      const turn = currentTurn.current ?? "";
      setEntries((current) => [
        ...current,
        { id: nextId.current++, kind: "error", text: payload.message as string, turn },
      ]);
    } else if (event === "done") {
      setBusy(false);
    }
  };

  const send = async (messageOverride?: string) => {
    const message = (messageOverride ?? draft).trim();
    if (!message || busy || !sessionId) {
      return;
    }
    const turn = newTurnId();
    currentTurn.current = turn;
    setDraft("");
    setNotice(null);
    setBusy(true);
    setSelectedTurn(null);
    setEntries((current) => [
      ...current,
      {
        id: nextId.current++,
        kind: "user",
        text: message,
        turn,
        at: new Date().toISOString(),
      },
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
        body: JSON.stringify({
          session: sessionId,
          message,
          turn,
          ...(modelId ? { modelId } : {}),
          executionPolicy,
        }),
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
          { id: nextId.current++, kind: "error", text, turn },
        ]);
      }
    } finally {
      if (abortRef.current === controller) {
        abortRef.current = null;
      }
      setBusy(false);
    }
  };

  const retryCommand = (command: string) => {
    void send(`请重试刚才失败的命令：${command}`);
  };

  useEffect(
    () => () => {
      if (copyTimer.current !== null) {
        window.clearTimeout(copyTimer.current);
      }
    },
    [],
  );

  const copyMessage = async (entry: UserEntry) => {
    try {
      await writeClipboard(entry.text);
      setCopiedTurn(entry.turn);
      if (copyTimer.current !== null) {
        window.clearTimeout(copyTimer.current);
      }
      copyTimer.current = window.setTimeout(() => {
        copyTimer.current = null;
        setCopiedTurn(null);
      }, 1600);
    } catch (cause) {
      setNotice(`复制失败：${cause instanceof Error ? cause.message : String(cause)}`);
    }
  };

  const removeSelectedTurn = async () => {
    if (!sessionId || !selectedTurn || deleting) {
      return;
    }
    setDeleting(true);
    setNotice(null);
    try {
      const result = await deleteChatTurn(sessionId, selectedTurn);
      setEntries((current) => current.filter((entry) => entry.turn !== selectedTurn));
      setSelectedTurn(null);
      setNotice(`已删除 ${result.messages} 条消息，后续对话不再包含这段内容`);
    } catch (cause) {
      setNotice(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setDeleting(false);
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
      currentTurn.current = null;
      setSelectedTurn(null);
      setCopiedTurn(null);
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

  const turns = groupTurns(entries);
  const hasModels = models.length > 0;
  const modelHint = hasModels
    ? null
    : backendReady
      ? "请先在下方模型菜单中选择「模型管理」添加模型"
      : "正在加载模型…";

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
              : "连接终端后，可以让我检查状态或执行运维命令。执行策略可以在输入框下方切换。"}
          </div>
        )}
        {turns.map((group) => (
          <ChatTurn
            key={group.turn || `orphan-${group.items[0]?.id ?? 0}`}
            group={group}
            selected={group.turn !== "" && selectedTurn === group.turn}
            copied={group.turn !== "" && copiedTurn === group.turn}
            busy={busy}
            deleting={deleting}
            onCopy={copyMessage}
            onSelect={setSelectedTurn}
            onDelete={() => void removeSelectedTurn()}
            onCancel={() => setSelectedTurn(null)}
            pendingAction={pendingAction}
            onDecideCommand={decideCommand}
            onRetryCommand={retryCommand}
          />
        ))}
        {busy && (
          <div className="chat-thinking" role="status">
            <span className="thinking-dots" aria-hidden="true">
              <i />
              <i />
              <i />
            </span>
            {livePhase(entries)}
          </div>
        )}
      </div>
      {notice && <div className="terminal-message">{notice}</div>}
      <div className="chat-input">
        <div className="chat-compose">
          <textarea
            rows={2}
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            onKeyDown={onKeyDown}
            placeholder={
              !sessionId
                ? "请先在终端连接主机"
                : (modelHint ?? "描述你要做的操作…（Enter 发送，Shift+Enter 换行）")
            }
            aria-label="发送给 AI 的消息"
            disabled={!sessionId || busy || historyLoading || !hasModels}
          />
          <div className="compose-tools">
            <ModelPicker
              models={models}
              value={modelId}
              onChange={setModelId}
              onManage={() => setSettingsOpen(true)}
              disabled={busy || !backendReady}
              loading={!backendReady}
            />
            <div className="execution-policy" role="group" aria-label="AI 命令执行策略">
              {executionPolicies.map((option) => (
                <button
                  key={option.value}
                  className={executionPolicy === option.value ? "policy-option active" : "policy-option"}
                  type="button"
                  title={option.title}
                  aria-pressed={executionPolicy === option.value}
                  disabled={busy}
                  onClick={() => changeExecutionPolicy(option.value)}
                >
                  {option.label}
                </button>
              ))}
            </div>
          </div>
        </div>
        {busy ? (
          <button className="ghost-button stop-button" type="button" onClick={() => void stop()}>
            停止
          </button>
        ) : (
          <button
            className="send-button"
            type="button"
            onClick={() => void send()}
            disabled={!sessionId || historyLoading || !hasModels || !draft.trim()}
          >
            发送
          </button>
        )}
      </div>
      {settingsOpen && (
        <ModelSettings
          onClose={() => setSettingsOpen(false)}
          onChanged={() => void loadModels()}
        />
      )}
    </aside>
  );
}

function toEntry(entry: ChatHistoryEntry): Entry {
  const turn = entry.turn ?? "";
  if (entry.kind === "command") {
    return {
      id: entry.id,
      turn,
      at: entry.at,
      kind: "command",
      command: entry.command,
      state: entry.state as CommandState,
      status: entry.status,
      exitCode: entry.exitCode,
      durationMs: entry.durationMs,
      note: entry.message ?? entry.reason,
      timeoutSeconds: entry.timeoutSeconds,
      output: entry.output,
      approvalToken: entry.approvalToken,
    };
  }
  return { id: entry.id, turn, at: entry.at, kind: entry.kind, text: entry.text };
}

function groupTurns(entries: Entry[]): TurnGroup[] {
  const groups: TurnGroup[] = [];
  for (const entry of entries) {
    let group = groups[groups.length - 1];
    if (!group || group.turn !== entry.turn) {
      group = { turn: entry.turn, user: null, items: [] };
      groups.push(group);
    }
    if (entry.kind === "user") {
      group.user ??= entry;
    } else {
      group.items.push(entry);
    }
  }
  return groups;
}

interface ChatTurnProps {
  group: TurnGroup;
  selected: boolean;
  copied: boolean;
  busy: boolean;
  deleting: boolean;
  onCopy: (entry: UserEntry) => void;
  onSelect: (turn: string) => void;
  onDelete: () => void;
  onCancel: () => void;
  pendingAction: string | null;
  onDecideCommand: (token: string, approved: boolean) => void;
  onRetryCommand: (command: string) => void;
}

function ChatTurn({
  group,
  selected,
  copied,
  busy,
  deleting,
  onCopy,
  onSelect,
  onDelete,
  onCancel,
  pendingAction,
  onDecideCommand,
  onRetryCommand,
}: ChatTurnProps) {
  const user = group.user;
  const selectable = group.turn !== "" && !busy;
  return (
    <div className={selected ? "chat-turn chat-turn-selected" : "chat-turn"}>
      {user && (
        <div className="turn-user">
          <div className="message message-user">{user.text}</div>
          <div className="message-actions">
            <span className="message-time">{copied ? "已复制" : formatTime(user.at)}</span>
            <button
              className="icon-action"
              type="button"
              title="复制这条消息"
              aria-label="复制这条消息"
              onClick={() => onCopy(user)}
            >
              <CopyIcon />
            </button>
            <button
              className="icon-action"
              type="button"
              title={
                busy
                  ? "对话进行中，暂时不能删除"
                  : selectable
                    ? "删除这条消息和这一轮的 AI 回复"
                    : "无法删除历史消息"
              }
              aria-label="删除这一轮对话"
              disabled={!selectable || deleting}
              onClick={() => onSelect(group.turn)}
            >
              <TrashIcon />
            </button>
          </div>
        </div>
      )}
      {selected && (
        <div className="turn-delete-bar">
          <span>已选中这一轮对话，删除后不会再发送给大模型。</span>
          <div className="turn-delete-buttons">
            <button className="ghost-button" type="button" onClick={onCancel} disabled={deleting}>
              取消
            </button>
            <button className="danger-button" type="button" onClick={onDelete} disabled={deleting}>
              {deleting ? "删除中…" : "删除"}
            </button>
          </div>
        </div>
      )}
      {group.items.length > 0 && (
        <div className="turn-items">
          {group.items.map((entry) =>
            renderEntry(entry, pendingAction, onDecideCommand, onRetryCommand),
          )}
        </div>
      )}
    </div>
  );
}

function renderEntry(
  entry: Entry,
  pendingAction: string | null,
  onDecideCommand: (token: string, approved: boolean) => void,
  onRetryCommand: (command: string) => void,
) {
  switch (entry.kind) {
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
      return (
        <CommandCard
          key={entry.id}
          entry={entry}
          pendingAction={pendingAction}
          onDecide={onDecideCommand}
          onRetry={onRetryCommand}
        />
      );
    default:
      return null;
  }
}

function CommandCard({
  entry,
  pendingAction,
  onDecide,
  onRetry,
}: {
  entry: Extract<Entry, { kind: "command" }>;
  pendingAction: string | null;
  onDecide: (token: string, approved: boolean) => void;
  onRetry: (command: string) => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const meta = commandMeta(entry);
  const failed =
    entry.state === "finished" &&
    ((entry.status !== undefined && entry.status !== "completed") ||
      (entry.exitCode !== undefined && entry.exitCode !== 0));
  return (
    <div className={`command command-${entry.state}`}>
      <div className="command-head">
        <span className="command-tool">
          <TerminalIcon />
          执行命令
        </span>
        <span className={meta.bad ? "command-meta command-meta-bad" : "command-meta"}>
          {meta.text}
        </span>
      </div>
      <div className="command-line-row">
        <div className="command-line">{entry.command}</div>
        <button
          className="icon-action"
          type="button"
          title="复制命令"
          aria-label="复制命令"
          onClick={() => void copyCommandText(entry.command)}
        >
          <CopyIcon />
        </button>
      </div>
      {entry.output && (
        <div className="command-output-wrap">
          <div className="command-output-actions">
            <button
              className="icon-action"
              type="button"
              title={expanded ? "收起命令输出" : "查看命令输出"}
              aria-label={expanded ? "收起命令输出" : "查看命令输出"}
              onClick={() => setExpanded((current) => !current)}
            >
              {expanded ? "⌃" : "⌄"}
            </button>
            <button
              className="icon-action"
              type="button"
              title="复制命令输出"
              aria-label="复制命令输出"
              onClick={() => void copyCommandText(entry.output ?? "")}
            >
              <CopyIcon />
            </button>
          </div>
          {expanded && <pre className="command-output">{entry.output}</pre>}
        </div>
      )}
      {entry.state === "pending" && entry.approvalToken && (
        <div className="command-approval">
          <span>等待确认后才会写入终端</span>
          <div className="command-approval-actions">
            <button
              className="ghost-button"
              type="button"
              disabled={pendingAction !== null}
              onClick={() => onDecide(entry.approvalToken!, false)}
            >
              拒绝
            </button>
            <button
              className="connect-button"
              type="button"
              disabled={pendingAction !== null}
              onClick={() => onDecide(entry.approvalToken!, true)}
            >
              {pendingAction === entry.approvalToken ? "处理中…" : "执行"}
            </button>
          </div>
        </div>
      )}
      {failed && (
        <div className="command-retry">
          <button
            className="ghost-button"
            type="button"
            title="让 AI 基于当前会话重试这条命令"
            onClick={() => onRetry(entry.command)}
          >
            重试此步骤
          </button>
        </div>
      )}
    </div>
  );
}

function livePhase(entries: Entry[]): string {
  for (let index = entries.length - 1; index >= 0; index -= 1) {
    const entry = entries[index];
    if (entry.kind === "ai") {
      if (entry.text.trim()) {
        return "正在回复…";
      }
      continue;
    }
    if (entry.kind === "command") {
      if (entry.state === "pending") return "等待确认…";
      return entry.state === "started" ? "正在执行命令…" : "正在分析执行结果…";
    }
    return "正在思考…";
  }
  return "正在思考…";
}

function formatTime(at?: string): string {
  const date = at ? new Date(at) : new Date();
  if (Number.isNaN(date.getTime())) {
    return "";
  }
  return date.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" });
}

function newTurnId(): string {
  if (typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function writeClipboard(text: string): Promise<void> {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return;
    } catch {
      // The webview can reject the async clipboard; fall back to the legacy path.
    }
  }
  const area = document.createElement("textarea");
  area.value = text;
  area.setAttribute("readonly", "");
  area.style.position = "fixed";
  area.style.top = "0";
  area.style.opacity = "0";
  document.body.appendChild(area);
  area.select();
  const ok = document.execCommand("copy");
  document.body.removeChild(area);
  if (!ok) {
    throw new Error("浏览器拒绝了剪贴板写入");
  }
}

async function copyCommandText(text: string): Promise<void> {
  try {
    await writeClipboard(text);
  } catch (cause) {
    console.error("command output copy failed", cause);
  }
}

function CopyIcon() {
  return (
    <svg
      width="13"
      height="13"
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.3"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <rect x="5.5" y="5.5" width="8" height="8" rx="1.8" />
      <path d="M10.5 3.4H4A1.4 1.4 0 0 0 2.6 4.8v6.6" />
    </svg>
  );
}

function TrashIcon() {
  return (
    <svg
      width="13"
      height="13"
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.3"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M2.8 4.4h10.4" />
      <path d="M6.4 4.4V3.1a.7.7 0 0 1 .7-.7h1.8a.7.7 0 0 1 .7.7v1.3" />
      <path d="m4.6 4.4.6 8a1 1 0 0 0 1 .9h3.6a1 1 0 0 0 1-.9l.6-8" />
      <path d="M6.8 6.9v3.8M9.2 6.9v3.8" />
    </svg>
  );
}

function TerminalIcon() {
  return (
    <svg
      width="12"
      height="12"
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.3"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <rect x="1.8" y="2.8" width="12.4" height="10.4" rx="2" />
      <path d="m4.8 6.3 2 1.7-2 1.7M8.6 9.9h2.6" />
    </svg>
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
  if (entry.state === "pending") {
    return { text: "等待确认", bad: false };
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
