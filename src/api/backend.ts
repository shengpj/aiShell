import { invoke } from "@tauri-apps/api/core";

export type BackendStatus = "starting" | "ready" | "failed";

export interface BackendInfo {
  status: BackendStatus;
  baseUrl: string;
  pid: number | null;
  error: string | null;
}

export interface BackendHealth {
  status: string;
  model: string;
}

export interface BackendTicket {
  ticket: string;
  expiresAt: string;
}

export function isTauri(): boolean {
  return "__TAURI_INTERNALS__" in window;
}

export async function getBackendInfo(): Promise<BackendInfo> {
  return invoke<BackendInfo>("backend_info");
}

export async function getBackendHealth(): Promise<BackendHealth> {
  return invoke<BackendHealth>("backend_health");
}

export async function getBackendTicket(purpose: string): Promise<BackendTicket> {
  return invoke<BackendTicket>("backend_ticket", { purpose });
}

export function websocketUrl(baseUrl: string, path: string, ticket: string): string {
  const wsBase = baseUrl.replace(/^http/, "ws");
  return `${wsBase}${path}?ticket=${encodeURIComponent(ticket)}`;
}
