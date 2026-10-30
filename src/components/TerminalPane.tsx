import { FitAddon } from "@xterm/addon-fit";
import { Terminal } from "@xterm/xterm";
import "@xterm/xterm/css/xterm.css";
import { useEffect, useRef, useState } from "react";
import { getBackendInfo, getBackendTicket, websocketUrl } from "../api/backend";
import { loadCommandHistory, type CommandHistoryEntry } from "../api/commands";
import type { ProfileConnection } from "../api/hosts";

type Phase = "idle" | "connecting" | "open" | "closed";
type AiState = "running" | "takeover";
type HookState = "installing" | "ready" | "degraded" | "unavailable";

interface ControlMessage {
  type: string;
  message?: string;
  session?: string;
  state?: string;
  command?: string;
  reason?: string;
  shell?: string;
}

interface TerminalPaneProps {
  onSessionChange?: (session: string | null) => void;
  onAskAi?: (prompt: string) => void;
  connection?: (ProfileConnection & { nonce: number }) | null;
}

function hookLabel(hook: { state: HookState; shell?: string }) {
  if (hook.state === "ready") {
    return hook.shell ? `审计就绪 · ${hook.shell}` : "审计就绪";
  }
  if (hook.state === "degraded") {
    return hook.shell ? `审计受限 · ${hook.shell}` : "审计受限";
  }
  return hook.state === "installing" ? "审计安装中" : "审计降级";
}

function hookTitle(hook: { state: HookState; message?: string }) {
  if (hook.state === "ready") {
    return "你输入的每条命令都会记入审计日志（命令、退出码、工作目录）";
  }
  if (hook.state === "installing") {
    return "正在向当前 shell 注入审计钩子";
  }
  if (hook.state === "degraded") {
    return hook.message ?? "当前 shell 设置会让部分命令无法进入审计";
  }
  return hook.message ?? "该 shell 不支持用户命令审计，AI 命令仍会记录";
}

export default function TerminalPane({ onSessionChange, onAskAi, connection }: TerminalPaneProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const termRef = useRef<Terminal | null>(null);
  const socketRef = useRef<WebSocket | null>(null);
  const sessionListener = useRef(onSessionChange);
  const [phase, setPhase] = useState<Phase>("idle");
  const [message, setMessage] = useState<string | null>(null);
  const [reconnectAttempt, setReconnectAttempt] = useState(0);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [historyQuery, setHistoryQuery] = useState("");
  const [history, setHistory] = useState<CommandHistoryEntry[]>([]);
  const [historyTruncated, setHistoryTruncated] = useState(false);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyError, setHistoryError] = useState<string | null>(null);
  const [ai, setAi] = useState<{ state: AiState; command?: string; reason?: string } | null>(null);
  const [hook, setHook] = useState<{ state: HookState; shell?: string; message?: string } | null>(
    null,
  );
  const phaseRef = useRef<Phase>("idle");
  const connectionRef = useRef<ProfileConnection | null>(null);
  const reconnectTimer = useRef<number | null>(null);
  const reconnectAttemptRef = useRef(0);
  const intentionalClose = useRef(false);
  const openedSocket = useRef<WebSocket | null>(null);
  const remoteClosed = useRef(false);

  const updatePhase = (next: Phase) => {
    phaseRef.current = next;
    setPhase(next);
  };

  const clearReconnect = () => {
    if (reconnectTimer.current !== null) {
      window.clearTimeout(reconnectTimer.current);
      reconnectTimer.current = null;
    }
    reconnectAttemptRef.current = 0;
    setReconnectAttempt(0);
  };

  const closeSocket = (intentional: boolean) => {
    intentionalClose.current = intentional;
    const socket = socketRef.current;
    socketRef.current = null;
    if (socket && socket.readyState !== WebSocket.CLOSED) {
      socket.close();
    }
  };

  const scheduleReconnect = (request: ProfileConnection) => {
    if (intentionalClose.current || reconnectTimer.current !== null) {
      return;
    }
    const attempt = reconnectAttemptRef.current + 1;
    if (attempt > 5) {
      setMessage("连接已中断，自动重连已停止。点击“重新连接”再试。");
      return;
    }
    reconnectAttemptRef.current = attempt;
    setReconnectAttempt(attempt);
    const delay = [1000, 2000, 4000, 8000, 15000][attempt - 1];
    setMessage(`连接已中断，将在 ${Math.ceil(delay / 1000)} 秒后重连（第 ${attempt} 次）`);
    reconnectTimer.current = window.setTimeout(() => {
      reconnectTimer.current = null;
      void connect(request, true);
    }, delay);
  };

  useEffect(() => {
    if (!historyOpen || !sessionId) {
      return;
    }
    let cancelled = false;
    const timer = window.setTimeout(() => {
      setHistoryLoading(true);
      setHistoryError(null);
      void loadCommandHistory(sessionId, historyQuery)
        .then((result) => {
          if (!cancelled) {
            setHistory(result.entries);
            setHistoryTruncated(result.truncated);
          }
        })
        .catch((cause) => {
          if (!cancelled) {
            setHistoryError(cause instanceof Error ? cause.message : String(cause));
          }
        })
        .finally(() => {
          if (!cancelled) {
            setHistoryLoading(false);
          }
        });
    }, 180);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [historyOpen, historyQuery, sessionId]);

  useEffect(() => {
    sessionListener.current = onSessionChange;
  }, [onSessionChange]);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) {
      return;
    }
    const terminal = new Terminal({
      fontFamily: 'Consolas, "Cascadia Mono", "Courier New", monospace',
      fontSize: 13,
      cursorBlink: true,
      theme: {
        background: "#111110",
        foreground: "#eeeeeb",
        cursor: "#5cb870",
        cursorAccent: "#111110",
        selectionBackground: "#335942",
      },
    });
    const fit = new FitAddon();
    terminal.loadAddon(fit);
    terminal.open(container);
    fit.fit();
    termRef.current = terminal;

    const sendResize = () => {
      fit.fit();
      const socket = socketRef.current;
      if (socket?.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify({ type: "resize", cols: terminal.cols, rows: terminal.rows }));
      }
    };
    const observer = new ResizeObserver(sendResize);
    observer.observe(container);

    const input = terminal.onData((data) => {
      const socket = socketRef.current;
      if (socket?.readyState === WebSocket.OPEN) {
        socket.send(new TextEncoder().encode(data));
      }
    });

    return () => {
      input.dispose();
      observer.disconnect();
      clearReconnect();
      closeSocket(true);
      connectionRef.current = null;
      setSessionId(null);
      sessionListener.current?.(null);
      terminal.dispose();
      container.replaceChildren();
      termRef.current = null;
    };
  }, []);

  const connect = async (request: ProfileConnection, reconnect = false) => {
    const terminal = termRef.current;
    if (!terminal) {
      return;
    }
    if (!reconnect) {
      clearReconnect();
    }
    connectionRef.current = request;
    intentionalClose.current = !reconnect;
    remoteClosed.current = false;
    if (phaseRef.current === "connecting" || phaseRef.current === "open") {
      closeSocket(true);
      setAi(null);
      setHook(null);
      sessionListener.current?.(null);
    }
    setMessage(null);
    setAi(null);
    setHook(null);
    terminal.reset();
    updatePhase("connecting");
    try {
      const info = await getBackendInfo();
      if (info.status !== "ready") {
        throw new Error(info.error ?? "后端尚未就绪");
      }
      const { ticket } = await getBackendTicket("terminal");
      const socket = new WebSocket(websocketUrl(info.baseUrl, "/ws/terminal", ticket));
      socket.binaryType = "arraybuffer";
      socketRef.current = socket;
      openedSocket.current = null;

      socket.onopen = () => {
        socket.send(
          JSON.stringify({
            type: "open",
            profile: request.profileId,
            cols: terminal.cols,
            rows: terminal.rows,
          }),
        );
      };
      socket.onmessage = (event) => {
        if (typeof event.data !== "string") {
          terminal.write(new Uint8Array(event.data as ArrayBuffer));
          return;
        }
        const control = JSON.parse(event.data) as ControlMessage;
        if (control.type === "opened") {
          updatePhase("open");
          openedSocket.current = socket;
          intentionalClose.current = false;
          reconnectAttemptRef.current = 0;
          setReconnectAttempt(0);
          setMessage(null);
          const nextSession = control.session ?? null;
          setSessionId(nextSession);
          sessionListener.current?.(nextSession);
          terminal.focus();
        } else if (control.type === "ai") {
          if (control.state === "running" || control.state === "takeover") {
            setAi({ state: control.state, command: control.command, reason: control.reason });
          } else {
            setAi(null);
          }
        } else if (control.type === "hook") {
          if (
            control.state === "installing" ||
            control.state === "ready" ||
            control.state === "degraded" ||
            control.state === "unavailable"
          ) {
            setHook({ state: control.state, shell: control.shell, message: control.message });
          }
        } else if (control.type === "error") {
          updatePhase("closed");
          setAi(null);
          setHook(null);
          setSessionId(null);
          sessionListener.current?.(null);
          remoteClosed.current = true;
          setMessage(control.message ?? "连接失败");
          terminal.write(`\r\n\x1b[31m${control.message ?? "连接失败"}\x1b[0m\r\n`);
        } else if (control.type === "closed") {
          updatePhase("closed");
          setAi(null);
          setHook(null);
          setSessionId(null);
          sessionListener.current?.(null);
          const reason = control.message ?? "会话已结束";
          remoteClosed.current = !reason.toLowerCase().includes("connection lost");
          setMessage(reason);
          terminal.write(`\r\n\x1b[90m[session closed: ${reason}]\x1b[0m\r\n`);
          socket.close();
        }
      };
      socket.onerror = () => setMessage("WebSocket 连接失败，正在检查连接状态…");
      socket.onclose = () => {
        if (socketRef.current === socket) {
          socketRef.current = null;
          setAi(null);
          setHook(null);
          setSessionId(null);
          sessionListener.current?.(null);
          const wasOpen = openedSocket.current === socket;
          const wasConnecting = phaseRef.current === "connecting";
          updatePhase((phaseRef.current === "open" || phaseRef.current === "connecting") ? "closed" : phaseRef.current);
          if ((wasOpen || wasConnecting) && !intentionalClose.current && !remoteClosed.current) {
            scheduleReconnect(request);
          }
        }
      };
    } catch (cause) {
      updatePhase("closed");
      setMessage(cause instanceof Error ? cause.message : String(cause));
      if (reconnect) {
        scheduleReconnect(request);
      }
    }
  };

  const lastConnection = useRef(0);
  useEffect(() => {
    if (!connection || connection.nonce === lastConnection.current) {
      return;
    }
    lastConnection.current = connection.nonce;
    clearReconnect();
    intentionalClose.current = true;
    void connect(connection);
  }, [connection]);

  const disconnect = () => {
    clearReconnect();
    intentionalClose.current = true;
    closeSocket(true);
    connectionRef.current = null;
    setAi(null);
    setHook(null);
    setSessionId(null);
    sessionListener.current?.(null);
    updatePhase("closed");
    setMessage("已断开");
  };

  const reconnect = () => {
    const request = connectionRef.current;
    if (!request) {
      return;
    }
    clearReconnect();
    closeSocket(true);
    void connect(request);
  };

  const setTakeover = (enabled: boolean) => {
    const socket = socketRef.current;
    if (socket?.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify({ type: "takeover", enabled }));
    }
  };

  const busy = phase === "connecting" || phase === "open";

  const copyHistoryCommand = async (command: string) => {
    try {
      await navigator.clipboard.writeText(command);
    } catch {
      setMessage("复制命令失败");
    }
  };

  const rerunHistoryCommand = (entry: CommandHistoryEntry) => {
    const socket = socketRef.current;
    if (!socket || socket.readyState !== WebSocket.OPEN) {
      setMessage("终端尚未连接，无法执行历史命令");
      return;
    }
    if (!window.confirm(`在当前终端重新执行？\n\n${entry.command}`)) {
      return;
    }
    socket.send(new TextEncoder().encode(`${entry.command}\r`));
    setHistoryOpen(false);
  };

  const askAiAboutHistory = (entry: CommandHistoryEntry) => {
    const result = entry.exitCode === undefined ? "未知" : String(entry.exitCode);
    const output = entry.output ? `\n输出：\n${entry.output}` : "";
    onAskAi?.(
      `请分析这条历史命令的用途、风险和执行结果，并说明在当前主机上是否适合再次执行：\n命令：${entry.command}\n退出码：${result}${output}`,
    );
    setHistoryOpen(false);
  };

  return (
    <section className="pane terminal">
      <div className="pane-header">
        <span className="tab tab-active">终端</span>
        <div className="terminal-connect">
          {phase === "connecting" && (
            <span className="terminal-state terminal-state-pending">
              {reconnectAttempt > 0 ? `重连中 · 第 ${reconnectAttempt} 次` : "连接中"}
            </span>
          )}
          {phase === "closed" && connectionRef.current && reconnectAttempt === 0 && (
            <span className="terminal-state terminal-state-closed">已断开</span>
          )}
          {hook && (
            <span className={`hook-badge hook-${hook.state}`} title={hookTitle(hook)}>
              {hookLabel(hook)}
            </span>
          )}
          {ai && (
            <span
              className={`ai-badge ai-${ai.state}`}
              title={ai.command ? `AI: ${ai.command}` : undefined}
            >
              {ai.state === "takeover"
                ? ai.reason === "prompt"
                  ? "等待输入"
                  : "已接管"
                : "AI 执行中"}
            </span>
          )}
          {ai?.state === "running" && (
            <button className="ghost-button" onClick={() => setTakeover(true)}>
              接管输入
            </button>
          )}
          {ai?.state === "takeover" && (
            <button className="ghost-button" onClick={() => setTakeover(false)}>
              交还 AI
            </button>
          )}
          {busy && (
            <button className="ghost-button" onClick={disconnect}>
              断开
            </button>
          )}
          {phase === "closed" && connectionRef.current && (
            <button className="ghost-button" onClick={reconnect}>
              重新连接
            </button>
          )}
          {sessionId && (
            <button
              className="ghost-button"
              type="button"
              title="查看当前会话的命令历史"
              onClick={() => setHistoryOpen((current) => !current)}
            >
              历史
            </button>
          )}
        </div>
      </div>
      <div className="terminal-host" ref={containerRef} />
      {phase === "idle" && (
        <div className="terminal-empty">
          <p className="empty-title">还没有打开终端</p>
          <p className="empty-hint">在左侧选择已有连接，或点击“新建连接”。</p>
        </div>
      )}
      {message && (
        <div className={`terminal-message${reconnectAttempt > 0 ? " terminal-message-reconnecting" : ""}`}>
          {message}
        </div>
      )}
      {historyOpen && sessionId && (
        <aside className="terminal-history" aria-label="命令历史">
          <div className="terminal-history-header">
            <strong>命令历史</strong>
            <button
              className="icon-button"
              type="button"
              title="关闭命令历史"
              aria-label="关闭命令历史"
              onClick={() => setHistoryOpen(false)}
            >
              ×
            </button>
          </div>
          <input
            className="field history-search"
            value={historyQuery}
            onChange={(event) => setHistoryQuery(event.target.value)}
            placeholder="搜索命令或工作目录"
            aria-label="搜索命令历史"
            autoFocus
          />
          {historyLoading && <p className="empty-hint history-hint">读取历史…</p>}
          {historyError && <p className="conn-error history-hint">{historyError}</p>}
          {!historyLoading && !historyError && history.length === 0 && (
            <p className="empty-hint history-hint">当前会话还没有命令记录。</p>
          )}
          <div className="terminal-history-list">
            {history.map((entry, index) => (
              <article className="history-entry" key={`${entry.at ?? "entry"}-${index}`}>
                <div className="history-entry-top">
                  <span className={`history-initiator history-initiator-${entry.initiator ?? "unknown"}`}>
                    {entry.initiator === "ai" ? "AI" : "用户"}
                  </span>
                  <code>{entry.command}</code>
                </div>
                <div className="history-entry-meta">
                  {entry.cwd ?? "工作目录未知"}
                  {entry.exitCode !== undefined ? ` · 退出码 ${entry.exitCode}` : ""}
                  {entry.at ? ` · ${formatHistoryTime(entry.at)}` : ""}
                </div>
                {entry.output && <pre className="history-output">{entry.output}</pre>}
                <div className="history-entry-actions">
                  <button className="mini" type="button" onClick={() => void copyHistoryCommand(entry.command)}>
                    复制
                  </button>
                  <button className="mini" type="button" onClick={() => rerunHistoryCommand(entry)}>
                    再次执行
                  </button>
                  <button className="mini" type="button" onClick={() => askAiAboutHistory(entry)}>
                    发送给 AI
                  </button>
                </div>
              </article>
            ))}
          </div>
          {historyTruncated && <p className="empty-hint history-hint">仅显示最近 100 条记录。</p>}
        </aside>
      )}
    </section>
  );
}

function formatHistoryTime(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}
