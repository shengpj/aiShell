import { FitAddon } from "@xterm/addon-fit";
import { Terminal } from "@xterm/xterm";
import "@xterm/xterm/css/xterm.css";
import { useEffect, useRef, useState } from "react";
import { getBackendInfo, getBackendTicket, websocketUrl } from "../api/backend";
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

export default function TerminalPane({ onSessionChange, connection }: TerminalPaneProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const termRef = useRef<Terminal | null>(null);
  const socketRef = useRef<WebSocket | null>(null);
  const sessionListener = useRef(onSessionChange);
  const [phase, setPhase] = useState<Phase>("idle");
  const [message, setMessage] = useState<string | null>(null);
  const [ai, setAi] = useState<{ state: AiState; command?: string; reason?: string } | null>(null);
  const [hook, setHook] = useState<{ state: HookState; shell?: string; message?: string } | null>(
    null,
  );
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
      socketRef.current?.close();
      socketRef.current = null;
      sessionListener.current?.(null);
      terminal.dispose();
      container.replaceChildren();
      termRef.current = null;
    };
  }, []);

  const connect = async (request: ProfileConnection) => {
    const terminal = termRef.current;
    if (!terminal) {
      return;
    }
    if (phase === "connecting" || phase === "open") {
      socketRef.current?.close();
      socketRef.current = null;
      setAi(null);
      setHook(null);
      sessionListener.current?.(null);
    }
    setMessage(null);
    setAi(null);
    setHook(null);
    terminal.reset();
    setPhase("connecting");
    try {
      const info = await getBackendInfo();
      if (info.status !== "ready") {
        throw new Error(info.error ?? "后端尚未就绪");
      }
      const { ticket } = await getBackendTicket("terminal");
      const socket = new WebSocket(websocketUrl(info.baseUrl, "/ws/terminal", ticket));
      socket.binaryType = "arraybuffer";
      socketRef.current = socket;

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
          setPhase("open");
          setMessage(null);
          sessionListener.current?.(control.session ?? null);
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
          setPhase("closed");
          setAi(null);
          setHook(null);
          sessionListener.current?.(null);
          setMessage(control.message ?? "连接失败");
          terminal.write(`\r\n\x1b[31m${control.message ?? "连接失败"}\x1b[0m\r\n`);
        } else if (control.type === "closed") {
          setPhase("closed");
          setAi(null);
          setHook(null);
          sessionListener.current?.(null);
          setMessage(control.message ?? "会话已结束");
          terminal.write(`\r\n\x1b[90m[session closed: ${control.message ?? ""}]\x1b[0m\r\n`);
          socket.close();
        }
      };
      socket.onerror = () => setMessage("WebSocket 连接失败");
      socket.onclose = () => {
        if (socketRef.current === socket) {
          socketRef.current = null;
          setAi(null);
          setHook(null);
          sessionListener.current?.(null);
          setPhase((current) => (current === "open" || current === "connecting" ? "closed" : current));
        }
      };
    } catch (cause) {
      setPhase("closed");
      setMessage(cause instanceof Error ? cause.message : String(cause));
    }
  };

  const lastConnection = useRef(0);
  useEffect(() => {
    if (!connection || connection.nonce === lastConnection.current) {
      return;
    }
    lastConnection.current = connection.nonce;
    void connect(connection);
  }, [connection]);

  const disconnect = () => {
    socketRef.current?.close();
    socketRef.current = null;
    setAi(null);
    setHook(null);
    sessionListener.current?.(null);
    setPhase("closed");
    setMessage("已断开");
  };

  const setTakeover = (enabled: boolean) => {
    const socket = socketRef.current;
    if (socket?.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify({ type: "takeover", enabled }));
    }
  };

  const busy = phase === "connecting" || phase === "open";

  return (
    <section className="pane terminal">
      <div className="pane-header">
        <span className="tab tab-active">终端</span>
        <div className="terminal-connect">
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
        </div>
      </div>
      <div className="terminal-host" ref={containerRef} />
      {phase === "idle" && (
        <div className="terminal-empty">
          <p className="empty-title">还没有打开终端</p>
          <p className="empty-hint">在左侧选择已有连接，或点击“新建连接”。</p>
        </div>
      )}
      {message && <div className="terminal-message">{message}</div>}
    </section>
  );
}
