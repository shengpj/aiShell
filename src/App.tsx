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
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [connection, setConnection] = useState<(ProfileConnection & { nonce: number }) | null>(
    null,
  );
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
          connectedProfileId={sessionId ? connection?.profileId ?? null : null}
          onConnect={(next) => setConnection({ ...next, nonce: Date.now() })}
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
          <TerminalPane connection={connection} onSessionChange={setSessionId} />
          <FileTreePane sessionId={sessionId} />
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
        <ChatPane sessionId={sessionId} backendReady={info?.status === "ready"} />
      </main>

      <StatusBar preview={preview} info={info} health={health} />
    </div>
  );
}
