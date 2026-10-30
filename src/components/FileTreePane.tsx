import { Fragment, useEffect, useRef, useState, type ReactNode } from "react";
import { confirmAction } from "../api/confirm";
import {
  downloadFile,
  listFiles,
  previewFile,
  writeTextFile,
  type FileEntry,
  type FileListing,
  type FilePreview,
  type FileType,
} from "../api/files";
import { contextSuffix, limitAiContext } from "../utils/aiContext";
import FileIcon, { iconKind } from "./FileIcon";

function message(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause);
}

function formatSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  if (bytes < 1024 * 1024 * 1024) {
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  }
  return `${(bytes / 1024 / 1024 / 1024).toFixed(1)} GB`;
}

function formatTime(seconds: number): string {
  if (!seconds || seconds <= 0) {
    return "—";
  }
  const date = new Date(seconds * 1000);
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}`;
}

function typeLabel(type: FileType): string {
  return type === "dir" ? "目录" : type === "link" ? "链接" : "文件";
}

function parentPath(path: string): string {
  if (path === "/" || !path) {
    return "/";
  }
  const cut = path.lastIndexOf("/");
  return cut <= 0 ? "/" : path.slice(0, cut);
}

function pathChain(path: string): string[] {
  const parts = path.split("/").filter(Boolean);
  const chain = ["/"];
  let current = "";
  for (const part of parts) {
    current = `${current}/${part}`;
    chain.push(current);
  }
  return chain;
}

interface TreeRowProps {
  path: string;
  depth: number;
  expanded: boolean;
  current: boolean;
  onOpen: (path: string) => void;
  onToggle: (path: string) => void;
}

function TreeRow({ path, depth, expanded, current, onOpen, onToggle }: TreeRowProps) {
  const name = path === "/" ? "/" : path.slice(path.lastIndexOf("/") + 1);
  return (
    <button
      className={`tree-row${current ? " tree-selected" : ""}`}
      type="button"
      style={{ paddingLeft: 6 + depth * 12 }}
      title={path}
      aria-expanded={expanded}
      onClick={() => onOpen(path)}
    >
      <span
        className="tree-caret"
        title={expanded ? "收起子目录" : "展开子目录"}
        onClick={(event) => {
          event.stopPropagation();
          onToggle(path);
        }}
      >
        {expanded ? "▾" : "▸"}
      </span>
      <FileIcon kind={expanded ? "folder-open" : "folder"} className="tree-icon" />
      <span className="tree-name tree-dir">{name}</span>
    </button>
  );
}

interface FileTreePaneProps {
  sessionId: string | null;
  onAskAi?: (prompt: string) => void;
}

export default function FileTreePane({ sessionId, onAskAi }: FileTreePaneProps) {
  const [cwd, setCwd] = useState("/");
  const [listing, setListing] = useState<FileListing | null>(null);
  const [treeDirs, setTreeDirs] = useState<Record<string, FileEntry[]>>({});
  const [expanded, setExpanded] = useState<string[]>(["/"]);
  const [loadingDirs, setLoadingDirs] = useState<string[]>([]);
  const [loadingCwd, setLoadingCwd] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [previewPath, setPreviewPath] = useState<string | null>(null);
  const [preview, setPreview] = useState<FilePreview | null>(null);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [editText, setEditText] = useState("");
  const [editing, setEditing] = useState(false);
  const [fileActionBusy, setFileActionBusy] = useState(false);
  const [fileActionError, setFileActionError] = useState<string | null>(null);

  const sessionRef = useRef(sessionId);
  sessionRef.current = sessionId;
  const listingRequest = useRef(0);
  const previewRequest = useRef(0);
  const treeRef = useRef<Record<string, FileEntry[]>>({});
  const treeLoading = useRef<Set<string>>(new Set());
  const tableRef = useRef<HTMLDivElement | null>(null);

  const cacheTreeDir = (path: string, entries: FileEntry[]) => {
    treeRef.current = {
      ...treeRef.current,
      [path]: entries.filter((entry) => entry.type === "dir"),
    };
    setTreeDirs(treeRef.current);
  };

  const loadTreeDir = async (session: string, path: string) => {
    if (treeRef.current[path] || treeLoading.current.has(path)) {
      return;
    }
    treeLoading.current.add(path);
    setLoadingDirs((current) => [...current, path]);
    try {
      const result = await listFiles(session, path);
      if (sessionRef.current === session) {
        cacheTreeDir(path, result.entries);
      }
    } catch (cause) {
      if (sessionRef.current === session) {
        setError(message(cause));
      }
    } finally {
      treeLoading.current.delete(path);
      if (sessionRef.current === session) {
        setLoadingDirs((current) => current.filter((item) => item !== path));
      }
    }
  };

  const reveal = async (session: string, path: string) => {
    const chain = pathChain(path);
    setExpanded((current) => Array.from(new Set([...current, ...chain])));
    for (const dir of chain.slice(0, -1)) {
      if (sessionRef.current !== session) {
        return;
      }
      await loadTreeDir(session, dir);
    }
  };

  const loadDir = async (session: string, path: string) => {
    const token = ++listingRequest.current;
    setLoadingCwd(true);
    setError(null);
    try {
      const result = await listFiles(session, path);
      if (listingRequest.current !== token || sessionRef.current !== session) {
        return;
      }
      setListing(result);
      setNotice(result.truncated ? `目录条目过多，仅显示前 ${result.entries.length} 项。` : null);
      cacheTreeDir(path, result.entries);
      tableRef.current?.scrollTo({ top: 0 });
    } catch (cause) {
      if (listingRequest.current === token && sessionRef.current === session) {
        setError(message(cause));
      }
    } finally {
      if (listingRequest.current === token && sessionRef.current === session) {
        setLoadingCwd(false);
      }
    }
  };

  const navigate = (path: string) => {
    if (!sessionId) {
      return;
    }
    setCwd(path);
    previewRequest.current++;
    setPreviewPath(null);
    setPreview(null);
    setPreviewError(null);
    setEditText("");
    setEditing(false);
    setFileActionBusy(false);
    setFileActionError(null);
    void loadDir(sessionId, path);
    void reveal(sessionId, path);
  };

  useEffect(() => {
    listingRequest.current++;
    previewRequest.current++;
    treeRef.current = {};
    treeLoading.current.clear();
    setCwd("/");
    setListing(null);
    setTreeDirs({});
    setExpanded(["/"]);
    setLoadingDirs([]);
    setLoadingCwd(false);
    setError(null);
    setNotice(null);
    setPreviewPath(null);
    setPreview(null);
    setPreviewError(null);
    if (!sessionId) {
      return;
    }
    void loadDir(sessionId, "/");
  }, [sessionId]);

  useEffect(() => {
    if (!previewPath) {
      return;
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setPreviewPath(null);
        setPreview(null);
        setPreviewError(null);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [previewPath]);

  const toggleTreeDir = (path: string) => {
    if (expanded.includes(path)) {
      setExpanded((current) => current.filter((item) => item !== path));
      return;
    }
    setExpanded((current) => [...current, path]);
    if (sessionId) {
      void loadTreeDir(sessionId, path);
    }
  };

  const refresh = () => {
    if (!sessionId) {
      return;
    }
    treeRef.current = {};
    treeLoading.current.clear();
    setTreeDirs({});
    setExpanded(["/"]);
    setLoadingDirs([]);
    setError(null);
    void loadDir(sessionId, cwd);
    void reveal(sessionId, cwd);
  };

  const openPreview = async (entry: FileEntry) => {
    if (!sessionId) {
      return;
    }
    const token = ++previewRequest.current;
    setPreviewPath(entry.path);
    setPreview(null);
    setPreviewError(null);
      setEditText("");
      setEditing(false);
      setFileActionError(null);
    try {
      const result = await previewFile(sessionId, entry.path);
      if (previewRequest.current === token && sessionRef.current === sessionId) {
        setPreview(result);
        setEditText(result.kind === "text" ? (result.text ?? "") : "");
      }
    } catch (cause) {
      if (previewRequest.current === token) {
        setPreviewError(message(cause));
      }
    }
  };

  const closePreview = () => {
    previewRequest.current++;
    setPreviewPath(null);
    setPreview(null);
    setPreviewError(null);
    setEditText("");
    setEditing(false);
    setFileActionError(null);
  };

  const startEditing = () => {
    if (preview?.kind !== "text") {
      return;
    }
    setEditText(preview.text ?? "");
    setFileActionError(null);
    setEditing(true);
  };

  const cancelEditing = () => {
    setEditText(preview?.text ?? "");
    setFileActionError(null);
    setEditing(false);
  };

  const saveEditing = async () => {
    if (!sessionId || !preview || preview.kind !== "text" || fileActionBusy) {
      return;
    }
    if (
      !(await confirmAction(
        `保存远程文件？\n\n${preview.path}\n\n这会覆盖当前文件内容，并写入审计日志。`,
        "保存远程文件",
        "保存",
      ))
    ) {
      return;
    }
    setFileActionBusy(true);
    setFileActionError(null);
    try {
      const result = await writeTextFile(sessionId, preview.path, editText, preview.modifiedAt);
      setPreview((current) =>
        current
          ? {
              ...current,
              text: editText,
              size: result.size,
              modifiedAt: result.modifiedAt,
              truncated: false,
            }
          : current,
      );
      setEditing(false);
      setNotice(`已保存 ${preview.path}（${formatSize(result.size)}），写入已记入审计日志。`);
      void loadDir(sessionId, cwd);
    } catch (cause) {
      setFileActionError(message(cause));
    } finally {
      setFileActionBusy(false);
    }
  };

  const downloadPreview = async () => {
    if (!sessionId || !preview || fileActionBusy) {
      return;
    }
    setFileActionBusy(true);
    setFileActionError(null);
    try {
      const blob = await downloadFile(sessionId, preview.path);
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = preview.path.slice(preview.path.lastIndexOf("/") + 1) || "download";
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(url);
      setNotice(`已下载 ${preview.path}，下载动作已记入审计日志。`);
    } catch (cause) {
      setFileActionError(message(cause));
    } finally {
      setFileActionBusy(false);
    }
  };

  const askAiAboutPreview = () => {
    if (!preview || preview.kind !== "text" || !preview.text) {
      return;
    }
    const content = limitAiContext(preview.text);
    onAskAi?.(
      `请分析远程文件 ${preview.path} 的内容，重点说明配置错误、异常日志、风险和建议的修改或排查步骤。不要假设未提供的环境信息。\n\n文件内容：\n\`\`\`\n${content.text}\n\`\`\`${contextSuffix(content.truncated || preview.truncated)}`,
    );
    closePreview();
  };

  const treeRows: ReactNode[] = [];
  const walkTree = (path: string, depth: number) => {
    for (const dir of treeDirs[path] ?? []) {
      const isExpanded = expanded.includes(dir.path);
      treeRows.push(
        <TreeRow
          key={dir.path}
          path={dir.path}
          depth={depth}
          expanded={isExpanded}
          current={dir.path === cwd}
          onOpen={navigate}
          onToggle={toggleTreeDir}
        />,
      );
      if (!isExpanded) {
        continue;
      }
      if (treeDirs[dir.path]) {
        walkTree(dir.path, depth + 1);
      } else if (loadingDirs.includes(dir.path)) {
        treeRows.push(
          <p
            className="tree-loading"
            key={`loading:${dir.path}`}
            style={{ paddingLeft: 6 + (depth + 1) * 12 }}
          >
            加载中…
          </p>,
        );
      }
    }
  };
  walkTree("/", 1);

  const crumbs = pathChain(cwd);
  const entries = listing?.entries ?? [];

  return (
    <section className="pane filetree">
      <div className="pane-header">
        <span>文件</span>
        <span
          className="filetree-readonly"
          title="受控：下载和文本编辑都经过当前会话、大小限制、版本检查与审计"
        >
          受控
        </span>
        <button
          className="icon-button"
          type="button"
          title="刷新目录"
          onClick={refresh}
          disabled={!sessionId}
        >
          ⟳
        </button>
      </div>
      <div className="filetree-body">
        {!sessionId ? (
          <p className="empty-hint filetree-alone">连接终端后显示远程目录。</p>
        ) : (
          <>
            <div className="filetree-tree">
              <TreeRow
                path="/"
                depth={0}
                expanded={expanded.includes("/")}
                current={cwd === "/"}
                onOpen={navigate}
                onToggle={toggleTreeDir}
              />
              <div className="tree">{treeRows}</div>
              {loadingDirs.includes("/") && <p className="tree-loading">加载中…</p>}
            </div>
            <div className="file-main">
              <div className="file-toolbar">
                <button
                  className="mini"
                  type="button"
                  title="上一级"
                  onClick={() => navigate(parentPath(cwd))}
                  disabled={cwd === "/"}
                >
                  ↑
                </button>
                <div className="file-crumbs" title={cwd}>
                  <button
                    className="crumb"
                    type="button"
                    onClick={() => navigate("/")}
                    disabled={cwd === "/"}
                  >
                    /
                  </button>
                  {crumbs.slice(1).map((path, index) => (
                    <Fragment key={path}>
                      {index > 0 && <span className="crumb-sep">/</span>}
                      <button
                        className="crumb"
                        type="button"
                        onClick={() => navigate(path)}
                        disabled={path === cwd}
                      >
                        {path.slice(path.lastIndexOf("/") + 1)}
                      </button>
                    </Fragment>
                  ))}
                </div>
                {notice && <span className="file-notice">{notice}</span>}
              </div>
              {error && <p className="conn-error file-error">{error}</p>}
              <div className="file-table-wrap" ref={tableRef}>
                <table className="file-table">
                  <colgroup>
                    <col />
                    <col className="col-size" />
                    <col className="col-type" />
                    <col className="col-time" />
                    <col className="col-perms" />
                    <col className="col-owner" />
                  </colgroup>
                  <thead>
                    <tr>
                      <th>名称</th>
                      <th className="num">大小</th>
                      <th>类型</th>
                      <th>修改时间</th>
                      <th>权限</th>
                      <th>用户/用户组</th>
                    </tr>
                  </thead>
                  <tbody>
                    {entries.map((entry) => (
                      <tr key={entry.path} className={entry.type === "dir" ? "file-row-dir" : ""}>
                        <td className="file-name-cell">
                          <button
                            className={`file-name file-${entry.type}`}
                            type="button"
                            title={entry.path}
                            onClick={() =>
                              entry.type === "dir" ? navigate(entry.path) : void openPreview(entry)
                            }
                          >
                            <FileIcon kind={iconKind(entry.name, entry.type)} />
                            <span className="file-name-text">{entry.name}</span>
                          </button>
                        </td>
                        <td className="num">{entry.type === "dir" ? "—" : formatSize(entry.size)}</td>
                        <td>{typeLabel(entry.type)}</td>
                        <td className="file-mtime">{formatTime(entry.modifiedAt)}</td>
                        <td className="file-perms" title={`权限 ${entry.permissions}`}>
                          {entry.permissions || "—"}
                        </td>
                        <td className="file-owner" title={`${entry.owner}/${entry.group}`}>
                          {entry.owner}/{entry.group}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {!listing && !error && <p className="empty-hint file-empty">读取目录…</p>}
                {listing && entries.length === 0 && <p className="empty-hint file-empty">空目录</p>}
                {loadingCwd && listing && <p className="empty-hint file-empty">读取目录…</p>}
              </div>
            </div>
          </>
        )}
      </div>

      {previewPath && (
        <div className="file-preview">
          <div className="file-preview-head">
            <span className="file-preview-path" title={previewPath}>
              {previewPath}
            </span>
            {preview && <span className="file-preview-meta">{formatSize(preview.size)}</span>}
            {preview && (
              <button
                className="ghost-button"
                type="button"
                title="下载当前远程文件"
                onClick={() => void downloadPreview()}
                disabled={fileActionBusy || editing}
              >
                下载
              </button>
            )}
            {preview?.kind === "text" && !editing && (
              <button
                className="ghost-button"
                type="button"
                title="编辑当前文本文件"
                onClick={startEditing}
                disabled={fileActionBusy}
              >
                编辑
              </button>
            )}
            {preview?.kind === "text" && editing && (
              <>
                <button
                  className="connect-button"
                  type="button"
                  onClick={() => void saveEditing()}
                  disabled={fileActionBusy}
                >
                  {fileActionBusy ? "保存中…" : "保存"}
                </button>
                <button className="ghost-button" type="button" onClick={cancelEditing} disabled={fileActionBusy}>
                  取消
                </button>
              </>
            )}
            {preview?.kind === "text" && !editing && (
              <button
                className="ghost-button"
                type="button"
                title="把文件内容带入 AI 对话"
                onClick={askAiAboutPreview}
              >
                问 AI
              </button>
            )}
            <button className="ghost-button" type="button" onClick={closePreview}>
              关闭
            </button>
          </div>
          <div className="file-preview-body">
            {previewError && <p className="conn-error">{previewError}</p>}
            {fileActionError && <p className="conn-error">{fileActionError}</p>}
            {!preview && !previewError && <p className="empty-hint">读取文件…</p>}
            {preview?.kind === "text" && editing && (
              <textarea
                className="preview-editor"
                value={editText}
                onChange={(event) => setEditText(event.target.value)}
                spellCheck={false}
                aria-label="编辑远程文件内容"
              />
            )}
            {preview?.kind === "text" && !editing && <pre className="preview-text">{preview.text}</pre>}
            {preview?.kind === "image" && preview.data && (
              <img
                className="preview-image"
                src={`data:${preview.mime};base64,${preview.data}`}
                alt={preview.path}
              />
            )}
            {preview?.kind === "binary" && (
              <p className="empty-hint">二进制文件，暂不支持预览。</p>
            )}
            {preview?.kind === "too-large" && (
              <p className="empty-hint">文件过大（{formatSize(preview.size)}），超出预览上限。</p>
            )}
          </div>
          {preview?.truncated && <p className="file-preview-hint">仅显示文件前 256 KB。</p>}
        </div>
      )}
    </section>
  );
}
