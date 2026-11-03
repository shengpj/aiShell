import { useEffect, useMemo, useState } from "react";
import { downloadAudit, searchAudit, type AuditEntry } from "../api/audit";

type Filters = { from?: string; to?: string; session?: string; initiator?: string; kind?: string; status?: string; query?: string };

function message(cause: unknown) { return cause instanceof Error ? cause.message : String(cause); }
function iso(value: string) { return value ? new Date(value).toISOString() : undefined; }
function display(entry: AuditEntry, key: string) { const value = entry[key]; return value === undefined || value === null ? "" : String(value); }

export default function AuditCenter({ onClose }: { onClose: () => void }) {
  const [draft, setDraft] = useState<Filters>({});
  const [filters, setFilters] = useState<Filters>({});
  const [entries, setEntries] = useState<AuditEntry[]>([]);
  const [page, setPage] = useState(0);
  const [total, setTotal] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [selected, setSelected] = useState<AuditEntry | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const size = 50;

  const params = useMemo(() => ({ ...filters, page, size }), [filters, page]);
  useEffect(() => {
    let disposed = false;
    setBusy(true);
    void searchAudit(params).then((result) => {
      if (disposed) return;
      setEntries(result.entries ?? []); setTotal(result.total ?? 0); setHasMore(result.hasMore === true); setError(null);
    }).catch((cause) => { if (!disposed) setError(message(cause)); })
      .finally(() => { if (!disposed) setBusy(false); });
    return () => { disposed = true; };
  }, [params]);

  const update = (key: keyof Filters, value: string) => setDraft((current) => ({ ...current, [key]: value || undefined }));
  const apply = (event: React.FormEvent) => { event.preventDefault(); setPage(0); setFilters({ ...draft, from: iso(draft.from ?? ""), to: iso(draft.to ?? "") }); };
  const exportFile = async (format: "json" | "csv") => {
    try {
      const blob = await downloadAudit(filters, format);
      const url = URL.createObjectURL(blob); const link = document.createElement("a");
      link.href = url; link.download = `ai-shell-audit.${format}`; link.click(); URL.revokeObjectURL(url);
    } catch (cause) { setError(message(cause)); }
  };

  return <div className="modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
    <section className="modal audit-modal" role="dialog" aria-modal="true" aria-label="审计中心">
      <div className="modal-head"><span>审计中心</span><button className="icon-button" type="button" title="关闭" aria-label="关闭" onClick={onClose}>×</button></div>
      <form className="audit-filters" onSubmit={apply}>
        <input className="field" type="datetime-local" title="开始时间" onChange={(event) => update("from", event.target.value)} />
        <input className="field" type="datetime-local" title="结束时间" onChange={(event) => update("to", event.target.value)} />
        <input className="field" placeholder="Session / 主机" value={draft.session ?? ""} onChange={(event) => update("session", event.target.value)} />
        <select className="field" value={draft.initiator ?? ""} onChange={(event) => update("initiator", event.target.value)}><option value="">发起者</option><option value="user">用户</option><option value="ai">AI</option></select>
        <select className="field" value={draft.kind ?? ""} onChange={(event) => update("kind", event.target.value)}><option value="">事件类型</option><option value="command">命令</option><option value="refused">拒绝</option><option value="file_write">文件保存</option><option value="file_download">文件下载</option></select>
        <input className="field" placeholder="状态" value={draft.status ?? ""} onChange={(event) => update("status", event.target.value)} />
        <input className="field audit-query" placeholder="关键词" value={draft.query ?? ""} onChange={(event) => update("query", event.target.value)} />
        <button className="connect-button" type="submit">筛选</button>
        <button className="ghost-button" type="button" onClick={() => void exportFile("json")}>JSON</button>
        <button className="ghost-button" type="button" onClick={() => void exportFile("csv")}>CSV</button>
      </form>
      {error && <p className="conn-error audit-error">{error}</p>}
      <div className="audit-summary">{busy ? "加载中…" : `共 ${total} 条`} {total > 0 && `· 第 ${page + 1} 页`}</div>
      <div className="audit-table-wrap">
        <table className="audit-table"><thead><tr><th>时间</th><th>类型</th><th>发起者</th><th>Session</th><th>命令 / 路径</th><th>状态</th></tr></thead>
          <tbody>{entries.map((entry, index) => <tr key={`${display(entry, "at")}-${index}`} onClick={() => setSelected(entry)}>
            <td>{display(entry, "at")}</td><td>{display(entry, "kind")}</td><td>{display(entry, "initiator")}</td><td title={display(entry, "session")}>{display(entry, "session")}</td><td className="audit-main" title={display(entry, "command") || display(entry, "path")}>{display(entry, "command") || display(entry, "path")}</td><td>{display(entry, "status") || (entry.exitCode !== undefined ? `exit ${display(entry, "exitCode")}` : "")}</td>
          </tr>)}</tbody>
        </table>
        {!busy && entries.length === 0 && <p className="empty-hint audit-empty">没有匹配的审计记录</p>}
      </div>
      <div className="audit-footer"><button className="ghost-button" disabled={page === 0 || busy} onClick={() => setPage((value) => value - 1)}>上一页</button><button className="ghost-button" disabled={!hasMore || busy} onClick={() => setPage((value) => value + 1)}>下一页</button></div>
      {selected && <div className="audit-detail"><div className="audit-detail-head"><span>事件详情</span><button className="icon-button" type="button" title="关闭详情" aria-label="关闭详情" onClick={() => setSelected(null)}>×</button></div><pre>{JSON.stringify(selected, null, 2)}</pre></div>}
    </section>
  </div>;
}
