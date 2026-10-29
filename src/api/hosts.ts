import { getBackendInfo, getBackendTicket } from "./backend";

export type AuthType = "password" | "private_key";

export interface ProfileView {
  id: string;
  name: string;
  username: string;
  authType: AuthType;
  privateKeyPath: string | null;
  hasSecret: boolean;
}

export interface HostView {
  id: string;
  name: string;
  address: string;
  port: number;
  profiles: ProfileView[];
}

export interface HostInput {
  name: string;
  address: string;
  port: number;
}

export interface ProfileInput {
  name: string;
  username: string;
  authType: AuthType;
  privateKeyPath?: string | null;
  secret?: string;
}

export interface ProfileConnection {
  profileId: string;
  host: string;
  port: number;
  user: string;
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const info = await getBackendInfo();
  if (info.status !== "ready") {
    throw new Error(info.error ?? "后端尚未就绪");
  }
  const { ticket } = await getBackendTicket("hosts");
  const response = await fetch(`${info.baseUrl}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      "X-AI-Shell-Ticket": ticket,
      ...(init.headers ?? {}),
    },
  });
  if (response.status === 204) {
    return undefined as T;
  }
  const payload = await response.json().catch(() => null);
  if (!response.ok) {
    const detail = payload?.detail ?? payload?.message ?? payload?.title;
    throw new Error(typeof detail === "string" && detail ? detail : `请求失败（HTTP ${response.status}）`);
  }
  return payload as T;
}

export function listHosts(): Promise<HostView[]> {
  return request<HostView[]>("/api/hosts");
}

export function createHost(input: HostInput): Promise<HostView> {
  return request<HostView>("/api/hosts", { method: "POST", body: JSON.stringify(input) });
}

export function updateHost(id: string, input: HostInput): Promise<HostView> {
  return request<HostView>(`/api/hosts/${id}`, { method: "PUT", body: JSON.stringify(input) });
}

export function deleteHost(id: string): Promise<void> {
  return request<void>(`/api/hosts/${id}`, { method: "DELETE" });
}

export function createProfile(hostId: string, input: ProfileInput): Promise<ProfileView> {
  return request<ProfileView>(`/api/hosts/${hostId}/profiles`, {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function updateProfile(id: string, input: ProfileInput): Promise<ProfileView> {
  return request<ProfileView>(`/api/profiles/${id}`, {
    method: "PUT",
    body: JSON.stringify(input),
  });
}

export function deleteProfile(id: string): Promise<void> {
  return request<void>(`/api/profiles/${id}`, { method: "DELETE" });
}
