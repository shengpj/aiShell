import {
  useEffect,
  useRef,
  useState,
  type CSSProperties,
  type PointerEvent as ReactPointerEvent,
} from "react";
import ChatPane from "./components/ChatPane";
import ConnectionsPane from "./components/ConnectionsPane";
import FileTreePane from "./components/FileTreePane";
import StatusBar from "./components/StatusBar";
import TerminalPane from "./components/TerminalPane";
import {
  getBackendHealth,
  getBackendInfo,
  isTauri,
  type BackendHealth,
  type BackendInfo,
} from "./api/backend";
import type { ProfileConnection } from "./api/hosts";

const LAYOUT_KEY = "aishell.layout";
const SPLITTER_WIDTH = 6;
const LEFT_RANGE = { min: 180, max: 480 };
const RIGHT_RANGE = { min: 280, max: 640 };
const CENTER_MIN = 420;

type Layout = { left: number; right: number };
type WorkspaceTab = {
  id: string;
  connection: ProfileConnection & { nonce: number };
  sessionId: string | null;
};

const DEFAULT_LAYOUT: Layout = { left: 240, right: 360 };

function clampWidth(value: number, min: number, max: number) {
  return Math.round(Math.min(Math.max(value, min), Math.max(min, max)));
}

function storedWidth(value: unknown, fallback: number, range: { min: number; max: number }) {
  return typeof value === "number" && Number.isFinite(value)
    ? clampWidth(value, range.min, range.max)
    : fallback;
}

function loadLayout(): Layout {
  try {
    const parsed = JSON.parse(window.localStorage.getItem(LAYOUT_KEY) ?? "null") as Partial<
      Layout
    > | null;
    return {
      left: storedWidth(parsed?.left, DEFAULT_LAYOUT.left, LEFT_RANGE),
      right: storedWidth(parsed?.right, DEFAULT_LAYOUT.right, RIGHT_RANGE),
    };
  } catch {
    return DEFAULT_LAYOUT;
  }
}

export default function App() {
  const preview = !isTauri();
  const [info, setInfo] = useState<BackendInfo | null>(null);
  const [health, setHealth] = useState<BackendHealth | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tabs, setTabs] = useState<WorkspaceTab[]>([]);
  const [activeTabId, setActiveTabId] = useState<string | null>(null);
  const [aiPrefill, setAiPrefill] = useState<{ tabId: string; prompt: string } | null>(null);
  const workspaceRef = useRef<HTMLElement>(null);
  const dragRef = useRef<{ side: "left" | "right"; startX: number; start: Layout } | null>(null);
  const [layout, setLayout] = useState(loadLayout);
  const [dragging, setDragging] = useState(false);

  useEffect(() => {
    try {
      window.localStorage.setItem(LAYOUT_KEY, JSON.stringify(layout));
    } catch {
      // Storage can be unavailable; the layout then resets on the next launch.
    }
  }, [layout]);

  useEffect(() => {
    const fit = () => {
      const total = workspaceRef.current?.clientWidth ?? window.innerWidth;
      const available = total - CENTER_MIN - SPLITTER_WIDTH * 2;
      setLayout((current) => {
        const left = clampWidth(
          current.left,
          LEFT_RANGE.min,
          Math.min(LEFT_RANGE.max, available - current.right),
        );
        const right = clampWidth(
          current.right,
          RIGHT_RANGE.min,
          Math.min(RIGHT_RANGE.max, available - left),
        );
        return left === current.left && right === current.right ? current : { left, right };
      });
    };

    fit();
    window.addEventListener("resize", fit);
    return () => window.removeEventListener("resize", fit);
  }, []);

  useEffect(() => {
    if (preview) {
      return;
    }
    let disposed = false;

    const poll = async () => {
      try {
        const next = await getBackendInfo();
        if (disposed) return;
        setInfo(next);
        if (next.status === "ready") {
          const result = await getBackendHealth();
          if (!disposed) {
            setHealth(result);
            setError(null);
          }
        } else if (next.status === "failed") {
          setError(next.error);
        }
      } catch (cause) {
        if (!disposed) setError(String(cause));
      }
    };

    void poll();
    const timer = window.setInterval(() => void poll(), 3000);
    return () => {
      disposed = true;
      window.clearInterval(timer);
    };
  }, [preview]);

  const beginDrag = (side: "left" | "right") => (event: ReactPointerEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.currentTarget.setPointerCapture(event.pointerId);
    dragRef.current = { side, startX: event.clientX, start: layout };
    setDragging(true);
  };

  const moveDrag = (event: ReactPointerEvent<HTMLDivElement>) => {
    const drag = dragRef.current;
    if (!drag) return;

    const total = workspaceRef.current?.clientWidth ?? window.innerWidth;
    const available = total - CENTER_MIN - SPLITTER_WIDTH * 2;
    const delta = event.clientX - drag.startX;
    if (drag.side === "left") {
      const max = Math.min(LEFT_RANGE.max, available - drag.start.right);
      setLayout({
        left: clampWidth(drag.start.left + delta, LEFT_RANGE.min, max),
        right: drag.start.right,
      });
    } else {
      const max = Math.min(RIGHT_RANGE.max, available - drag.start.left);
      setLayout({
        left: drag.start.left,
        right: clampWidth(drag.start.right - delta, RIGHT_RANGE.min, max),
      });
    }
  };

  const endDrag = (event: ReactPointerEvent<HTMLDivElement>) => {
    dragRef.current = null;
    setDragging(false);
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
  };

  const openConnection = (next: ProfileConnection) => {
    const existing = tabs.find((tab) => tab.connection.profileId === next.profileId);
    if (existing) {
      setActiveTabId(existing.id);
      return;
    }
    const id = `${next.profileId}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    const tab: WorkspaceTab = {
      id,
      connection: { ...next, nonce: Date.now() },
      sessionId: null,
    };
    setTabs((current) => [...current, tab]);
    setActiveTabId(id);
  };

  const updateSession = (tabId: string, sessionId: string | null) => {
    setTabs((current) =>
      current.map((tab) => (tab.id === tabId ? { ...tab, sessionId } : tab)),
    );
  };

  const closeTab = (tabId: string) => {
    const index = tabs.findIndex((tab) => tab.id === tabId);
    const next = tabs.filter((tab) => tab.id !== tabId);
    setTabs(next);
    if (activeTabId === tabId) {
      setActiveTabId(next[index]?.id ?? next[index - 1]?.id ?? null);
    }
  };

  const connectedProfileIds = tabs
    .filter((tab) => tab.sessionId)
    .map((tab) => tab.connection.profileId);

  return (
    <div className="shell">
      <header className="titlebar">
        <span className="brand">AI Shell</span>
        <span className="titlebar-hint">本地 SSH 运维工作台</span>
      </header>

      {error && <div className="banner">后端不可用：{error}</div>}

      <main
        className={dragging ? "workspace dragging" : "workspace"}
        ref={workspaceRef}
        style={
          {
            "--pane-left": `${layout.left}px`,
            "--pane-right": `${layout.right}px`,
          } as CSSProperties
        }
      >
        <ConnectionsPane
          connectedProfileIds={connectedProfileIds}
          onConnect={openConnection}
        />
        <div
          className="splitter"
          role="separator"
          aria-orientation="vertical"
          aria-label="调整连接面板宽度"
          onPointerDown={beginDrag("left")}
          onPointerMove={moveDrag}
          onPointerUp={endDrag}
          onPointerCancel={endDrag}
        />
        <section className="center">
          <div className="session-tabs" role="tablist" aria-label="终端会话">
            {tabs.map((tab) => (
              <div
                className={`session-tab${tab.id === activeTabId ? " session-tab-active" : ""}`}
                key={tab.id}
                role="presentation"
              >
                <button
                  className="session-tab-main"
                  type="button"
                  role="tab"
                  aria-selected={tab.id === activeTabId}
                  onClick={() => setActiveTabId(tab.id)}
                >
                  <span className="session-tab-title">{tab.connection.user}@{tab.connection.host}</span>
                  <span className={`session-tab-status${tab.sessionId ? " session-tab-status-on" : ""}`} />
                </button>
                <button
                  className="session-tab-close"
                  type="button"
                  title="关闭会话"
                  aria-label={`关闭 ${tab.connection.user}@${tab.connection.host}`}
                  onClick={() => closeTab(tab.id)}
                >
                  ×
                </button>
              </div>
            ))}
            {tabs.length === 0 && <span className="session-tabs-empty">未打开会话</span>}
          </div>
          {tabs.length === 0 ? (
            <>
              <TerminalPane connection={null} />
              <FileTreePane sessionId={null} />
            </>
          ) : (
            tabs.map((tab) => (
              <div
                className={`workspace-tab-content${tab.id === activeTabId ? " workspace-tab-content-active" : ""}`}
                key={tab.id}
                aria-hidden={tab.id !== activeTabId}
              >
                <TerminalPane
                  connection={tab.connection}
                  onSessionChange={(sessionId) => updateSession(tab.id, sessionId)}
                  onAskAi={(prompt) => {
                    setActiveTabId(tab.id);
                    setAiPrefill({ tabId: tab.id, prompt });
                  }}
                />
                <FileTreePane
                  sessionId={tab.sessionId}
                  onAskAi={(prompt) => {
                    setActiveTabId(tab.id);
                    setAiPrefill({ tabId: tab.id, prompt });
                  }}
                />
              </div>
            ))
          )}
        </section>
        <div
          className="splitter"
          role="separator"
          aria-orientation="vertical"
          aria-label="调整 AI 面板宽度"
          onPointerDown={beginDrag("right")}
          onPointerMove={moveDrag}
          onPointerUp={endDrag}
          onPointerCancel={endDrag}
        />
        <section className="chat-workspace">
          {tabs.length === 0 ? (
            <ChatPane sessionId={null} backendReady={info?.status === "ready"} />
          ) : (
            tabs.map((tab) => (
              <div
                className={`workspace-chat-content${tab.id === activeTabId ? " workspace-chat-content-active" : ""}`}
                key={tab.id}
                aria-hidden={tab.id !== activeTabId}
              >
                <ChatPane
                  sessionId={tab.sessionId}
                  backendReady={info?.status === "ready"}
                  prefill={aiPrefill?.tabId === tab.id ? aiPrefill.prompt : null}
                  onPrefillConsumed={() => setAiPrefill(null)}
                />
              </div>
            ))
          )}
        </section>
      </main>

      <StatusBar preview={preview} info={info} health={health} />
    </div>
  );
}
