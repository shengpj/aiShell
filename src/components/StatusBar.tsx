import type { BackendHealth, BackendInfo } from "../api/backend";

interface StatusBarProps {
  preview: boolean;
  info: BackendInfo | null;
  health: BackendHealth | null;
}

function backendLabel(preview: boolean, info: BackendInfo | null): string {
  if (preview) return "浏览器预览（未连接桌面端）";
  if (!info) return "正在启动后端…";
  switch (info.status) {
    case "ready":
      return `后端已连接 · ${info.baseUrl.replace(/^https?:\/\//, "")}`;
    case "failed":
      return "后端启动失败";
    default:
      return "正在启动后端…";
  }
}

export default function StatusBar({ preview, info, health }: StatusBarProps) {
  const state = preview ? "preview" : (info?.status ?? "starting");

  return (
    <footer className="statusbar">
      <span className={`status status-${state}`}>
        <span className="status-dot" />
        {backendLabel(preview, info)}
      </span>
      <span className="status-meta">
        {health?.model ? `${health.model} · ` : ""}AI Shell 0.1.0
      </span>
    </footer>
  );
}
