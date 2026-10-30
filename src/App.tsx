import { useEffect, useState } from "react";
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

export default function App() {
  const preview = !isTauri();
  const [info, setInfo] = useState<BackendInfo | null>(null);
  const [health, setHealth] = useState<BackendHealth | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [connection, setConnection] = useState<(ProfileConnection & { nonce: number }) | null>(
    null,
  );

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

  return (
    <div className="shell">
      <header className="titlebar">
        <span className="brand">AI Shell</span>
        <span className="titlebar-hint">本地 SSH 运维工作台</span>
      </header>

      {error && <div className="banner">后端不可用：{error}</div>}

      <main className="workspace">
        <ConnectionsPane
          connectedProfileId={sessionId ? connection?.profileId ?? null : null}
          onConnect={(next) => setConnection({ ...next, nonce: Date.now() })}
        />
        <section className="center">
          <TerminalPane connection={connection} onSessionChange={setSessionId} />
          <FileTreePane sessionId={sessionId} />
        </section>
        <ChatPane sessionId={sessionId} backendReady={info?.status === "ready"} />
      </main>

      <StatusBar preview={preview} info={info} health={health} />
    </div>
  );
}
