import { open as openDialog } from "@tauri-apps/plugin-dialog";
import { useEffect, useState } from "react";
import { getBackendInfo, isTauri } from "../api/backend";
import { confirmDestructive } from "../api/confirm";
import {
  createHost,
  createProfile,
  deleteProfile,
  listHosts,
  updateHost,
  updateProfile,
  type HostInput,
  type HostView,
  type ProfileConnection,
  type ProfileInput,
  type ProfileView,
} from "../api/hosts";

/** One "connection": a host plus the profile (user, key, secret) used to reach it. */
interface ConnectionForm {
  /** Set when the host is already known (adding a profile or editing one). */
  hostId: string | null;
  /** Set when an existing profile is being edited; null means create. */
  profileId: string | null;
  name: string;
  address: string;
  port: string;
  username: string;
  privateKeyPath: string;
  secret: string;
  hasSecret: boolean;
  clearSecret: boolean;
}

function message(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause);
}

export default function ConnectionsPane({
  onConnect,
  connectedProfileId,
}: {
  onConnect: (connection: ProfileConnection) => void;
  connectedProfileId?: string | null;
}) {
  const [hosts, setHosts] = useState<HostView[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [busy, setBusy] = useState(false);
  const [connForm, setConnForm] = useState<ConnectionForm | null>(null);

  const load = async (): Promise<boolean> => {
    if (!isTauri()) {
      return false;
    }
    try {
      setHosts(await listHosts());
      setLoaded(true);
      setError(null);
      return true;
    } catch (cause) {
      setError(message(cause));
      return false;
    }
  };

  useEffect(() => {
    if (!isTauri()) {
      return;
    }
    let disposed = false;
    let timer: number | undefined;
    function retry(delayMs: number) {
      if (!disposed) {
        timer = window.setTimeout(() => void attempt(), delayMs);
      }
    }
    async function attempt() {
      try {
        const info = await getBackendInfo();
        if (disposed) {
          return;
        }
        if (info.status === "failed") {
          setError(info.error ?? "后端启动失败");
          return;
        }
        if (info.status !== "ready") {
          retry(1000);
          return;
        }
        // A cold start can reject the very first invoke/API call while the sidecar settles;
        // keep retrying instead of leaving the pane empty until some other action reloads it.
        if (!(await load())) {
          retry(1500);
        }
      } catch (cause) {
        if (!disposed) {
          setError(message(cause));
          retry(1000);
        }
      }
    }
    void attempt();
    return () => {
      disposed = true;
      if (timer !== undefined) {
        window.clearTimeout(timer);
      }
    };
  }, []);

  const openCreate = () => {
    setConnForm({
      hostId: null,
      profileId: null,
      name: "",
      address: "",
      port: "",
      username: "",
      privateKeyPath: "",
      secret: "",
      hasSecret: false,
      clearSecret: false,
    });
  };

  const openEditProfile = (host: HostView, profile: ProfileView) => {
    setConnForm({
      hostId: host.id,
      profileId: profile.id,
      name: profile.name,
      address: host.address,
      port: String(host.port),
      username: profile.username,
      privateKeyPath: profile.privateKeyPath ?? "",
      secret: "",
      hasSecret: profile.hasSecret,
      clearSecret: false,
    });
  };

  const choosePrivateKey = async () => {
    if (!connForm) {
      return;
    }
    try {
      const picked = await openDialog({
        multiple: false,
        directory: false,
        title: "选择私钥文件",
      });
      if (typeof picked === "string" && picked) {
        setConnForm({ ...connForm, privateKeyPath: picked });
      }
    } catch (cause) {
      setError(message(cause));
    }
  };

  const saveConnection = async () => {
    if (!connForm) {
      return;
    }
    const address = connForm.address.trim();
    const port = Number(connForm.port) || 22;
    const username = connForm.username.trim();
    if (!address || !username) {
      return;
    }
    setBusy(true);
    try {
      const keyPath = connForm.privateKeyPath.trim();
      const input: ProfileInput = {
        name: connForm.name.trim() || username,
        username,
        authType: keyPath ? "private_key" : "password",
        privateKeyPath: keyPath || null,
      };
      if (connForm.clearSecret) {
        input.secret = "";
      } else if (connForm.secret) {
        input.secret = connForm.secret;
      }

      if (connForm.profileId) {
        const host = hosts.find((candidate) => candidate.id === connForm.hostId);
        if (host && (host.address !== address || host.port !== port)) {
          const hostInput: HostInput = { name: host.name, address, port };
          await updateHost(host.id, hostInput);
        }
        await updateProfile(connForm.profileId, input);
        setConnForm(null);
        await load();
        return;
      }

      let hostId = connForm.hostId;
      if (!hostId) {
        const existing = hosts.find(
          (host) =>
            host.address.toLowerCase() === address.toLowerCase() && host.port === port,
        );
        hostId = existing
          ? existing.id
          : (await createHost({ name: connForm.name.trim() || address, address, port })).id;
      }
      const profile = await createProfile(hostId, input);
      setConnForm(null);
      await load();
      onConnect({ profileId: profile.id, host: address, port, user: username });
    } catch (cause) {
      setError(message(cause));
    } finally {
      setBusy(false);
    }
  };

  const removeProfile = async (host: HostView, profile: ProfileView) => {
    const lastOnHost = host.profiles.length === 1;
    const prompt = lastOnHost
      ? `删除连接 ${profile.name}？这是该主机下的最后一个连接，主机记录会一并移除，审计记录保留。`
      : `删除连接 ${profile.name}？该主机下的其他连接不受影响。`;
    const ok = await confirmDestructive(prompt, "删除连接");
    if (!ok) {
      return;
    }
    try {
      await deleteProfile(profile.id);
      if (connForm?.profileId === profile.id) {
        setConnForm(null);
      }
      await load();
    } catch (cause) {
      setError(message(cause));
    }
  };

  const authLabel = (profile: ProfileView) => {
    if (profile.authType === "private_key") {
      return profile.hasSecret ? "私钥 · 有口令" : "私钥";
    }
    return profile.hasSecret ? "密码 · 已保存" : "密码";
  };

  const usesKey = Boolean(connForm?.privateKeyPath.trim());
  // The backend already orders hosts and profiles newest first; an empty group cannot exist
  // (the host is retired with its last connection).
  const connections = hosts.flatMap((host) =>
    host.profiles.map((profile) => ({ host, profile })),
  );
  const editingHost = connForm?.hostId
    ? hosts.find((host) => host.id === connForm.hostId)
    : undefined;
  const sharedHost = Boolean(editingHost && editingHost.profiles.length > 1);

  return (
    <aside className="pane connections">
      <div className="pane-header">
        <span>连接</span>
      </div>
      <div className="pane-body">
        {error && <p className="conn-error">{error}</p>}

        {connForm && (
          <form
            className="conn-form"
            onSubmit={(event) => {
              event.preventDefault();
              void saveConnection();
            }}
          >
            <span className="form-title">{connForm.profileId ? "编辑连接" : "新建连接"}</span>
            <label>
              名称
              <input
                className="field field-wide"
                value={connForm.name}
                onChange={(event) => setConnForm({ ...connForm, name: event.target.value })}
                spellCheck={false}
              />
            </label>
            <label>
              地址
              <input
                className="field field-wide"
                value={connForm.address}
                onChange={(event) => setConnForm({ ...connForm, address: event.target.value })}
                spellCheck={false}
              />
            </label>
            <label>
              端口（默认 22）
              <input
                className="field field-port"
                value={connForm.port}
                onChange={(event) =>
                  setConnForm({ ...connForm, port: event.target.value.replace(/\D/g, "") })
                }
                inputMode="numeric"
              />
            </label>
            <label>
              用户名
              <input
                className="field field-wide"
                value={connForm.username}
                onChange={(event) => setConnForm({ ...connForm, username: event.target.value })}
                spellCheck={false}
              />
            </label>
            <label>
              私钥文件（可选）
              <span className="field-row">
                <input
                  className="field field-wide"
                  value={connForm.privateKeyPath}
                  readOnly
                  placeholder="未选择，则使用密码登录"
                  title={connForm.privateKeyPath || undefined}
                />
                <button className="ghost-button" type="button" onClick={() => void choosePrivateKey()}>
                  选择文件
                </button>
                {connForm.privateKeyPath && (
                  <button
                    className="ghost-button"
                    type="button"
                    onClick={() => setConnForm({ ...connForm, privateKeyPath: "" })}
                  >
                    清除
                  </button>
                )}
              </span>
            </label>
            <label>
              {usesKey ? "私钥口令（可选）" : "密码（可选）"}
              <input
                className="field field-wide"
                type="password"
                value={connForm.secret}
                onChange={(event) => setConnForm({ ...connForm, secret: event.target.value })}
                placeholder={connForm.hasSecret ? "已保存，留空保持不变" : "留空则在终端中输入"}
                autoComplete="new-password"
              />
            </label>
            {connForm.hasSecret && (
              <label className="checkbox">
                <input
                  type="checkbox"
                  checked={connForm.clearSecret}
                  onChange={(event) =>
                    setConnForm({ ...connForm, clearSecret: event.target.checked })
                  }
                />
                清除已保存的凭据
              </label>
            )}
            <p className="form-hint">
              凭据用 AES-GCM 加密后保存在本机，主密钥在 Windows 凭据管理器。
            </p>
            {sharedHost && (
              <p className="form-hint">
                同一主机下的 {editingHost?.profiles.length} 条连接共用地址与端口，修改会一起生效。
              </p>
            )}
            <div className="form-actions">
              <button
                className="connect-button"
                type="submit"
                disabled={busy || !connForm.address.trim() || !connForm.username.trim()}
              >
                {connForm.profileId ? "保存" : "连接"}
              </button>
              <button className="ghost-button" type="button" onClick={() => setConnForm(null)}>
                取消
              </button>
            </div>
          </form>
        )}

        {!connForm && (
          <button className="conn-add" type="button" onClick={openCreate}>
            <span className="conn-add-plus">+</span>
            新建连接
          </button>
        )}

        {!loaded && !error && (
          <p className="empty-hint">正在加载连接…</p>
        )}

        {loaded && connections.length === 0 && !connForm && (
          <>
            <p className="empty-title">暂无连接</p>
            <p className="empty-hint">点击“新建连接”，保存后即可打开终端。</p>
          </>
        )}

        <div className="connection-list">
          {connections.map(({ host, profile }) => (
            <div
              className={`profile-row${
                profile.id === connectedProfileId ? " profile-connected" : ""
              }`}
              key={profile.id}
            >
              {profile.id === connectedProfileId && (
                <span className="profile-dot" title="当前已连接" />
              )}
              <button
                className="profile-main"
                type="button"
                title={`连接 ${profile.name}（${profile.username}@${host.address}:${host.port} · ${authLabel(profile)}）`}
                onClick={() =>
                  onConnect({
                    profileId: profile.id,
                    host: host.address,
                    port: host.port,
                    user: profile.username,
                  })
                }
              >
                <span className="profile-name">{profile.name}</span>
                <span className="profile-meta">
                  {profile.username}@{host.address}:{host.port}
                </span>
              </button>
              <div className="profile-actions">
                <button
                  className="mini"
                  type="button"
                  title="编辑连接配置"
                  onClick={() => openEditProfile(host, profile)}
                >
                  编辑
                </button>
                <button
                  className="mini mini-danger"
                  type="button"
                  title="删除连接配置"
                  onClick={() => void removeProfile(host, profile)}
                >
                  删除
                </button>
              </div>
            </div>
          ))}
        </div>
      </div>
    </aside>
  );
}
