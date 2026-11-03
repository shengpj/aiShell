import { useEffect, useMemo, useState } from "react";
import { confirmDestructive } from "../api/confirm";
import { createCustomRunbook, deleteCustomRunbook, listCustomRunbooks, updateCustomRunbook, type CustomRunbook } from "../api/runbooks";
import { buildRunbookPrompt, RUNBOOKS, type Runbook } from "../runbooks";

interface RunbookPickerProps {
  onClose: () => void;
  onUse: (prompt: string) => void;
}

export default function RunbookPicker({ onClose, onUse }: RunbookPickerProps) {
  const [custom, setCustom] = useState<CustomRunbook[]>([]);
  const [selectedId, setSelectedId] = useState(RUNBOOKS[0]?.id ?? "");
  const [values, setValues] = useState<Record<string, string>>({});
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<CustomRunbook>({ id: "", title: "", summary: "", category: "自定义", fields: [], steps: [""], success: [""], version: 1, createdAt: "", updatedAt: "" });
  const [error, setError] = useState<string | null>(null);
  const all = useMemo<Runbook[]>(() => [...RUNBOOKS, ...custom.map((item) => item as Runbook)], [custom]);
  const selected = useMemo(
    () => all.find((runbook) => runbook.id === selectedId) ?? all[0],
    [all, selectedId],
  );

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  useEffect(() => { void listCustomRunbooks().then(setCustom).catch(() => undefined); }, []);

  if (!selected) {
    return null;
  }

  const canUse = selected.fields.every((field) => values[field.id]?.trim());
  const isCustom = custom.some((item) => item.id === selected.id);
  const save = async () => {
    try {
      const input = { ...draft, steps: draft.steps.filter(Boolean), success: draft.success.filter(Boolean) };
      const saved = draft.id ? await updateCustomRunbook(draft.id, input) : await createCustomRunbook(input);
      setCustom((current) => draft.id ? current.map((item) => item.id === saved.id ? saved : item) : [saved, ...current]);
      setSelectedId(saved.id); setEditing(false); setError(null);
    } catch (cause) { setError(cause instanceof Error ? cause.message : String(cause)); }
  };
  const remove = async () => {
    if (!isCustom || !(await confirmDestructive("删除 Runbook「" + selected.title + "」？", "删除 Runbook"))) return;
    try { await deleteCustomRunbook(selected.id); setCustom((current) => current.filter((item) => item.id !== selected.id)); setSelectedId(RUNBOOKS[0]?.id ?? ""); } catch (cause) { setError(cause instanceof Error ? cause.message : String(cause)); }
  };

  return (
    <div
      className="modal-backdrop"
      role="presentation"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) {
          onClose();
        }
      }}
    >
      <div className="modal runbook-modal" role="dialog" aria-modal="true" aria-label="运维 Runbook">
        <div className="modal-head">
          <span>运维 Runbook</span>
          <button className="icon-button" type="button" onClick={onClose} aria-label="关闭">
            ×
          </button>
        </div>
        <div className="runbook-layout">
          <div className="runbook-list" role="listbox" aria-label="选择 Runbook">
            {all.map((runbook) => (
              <button
                className={runbook.id === selected.id ? "runbook-card selected" : "runbook-card"}
                type="button"
                role="option"
                aria-selected={runbook.id === selected.id}
                key={runbook.id}
                onClick={() => {
                  setSelectedId(runbook.id);
                  setValues({});
                }}
              >
                <span className="runbook-card-title">{runbook.title}</span>
                <span className="runbook-card-category">{runbook.category}</span>
                <span className="runbook-card-summary">{runbook.summary}</span>
              </button>
            ))}
            <button className="runbook-card runbook-add" type="button" onClick={() => { setDraft({ id: "", title: "", summary: "", category: "自定义", fields: [], steps: [""], success: [""], version: 1, createdAt: "", updatedAt: "" }); setEditing(true); }}>+ 新建 Runbook</button>
          </div>
          <div className="runbook-detail">
            {error && <p className="conn-error">{error}</p>}
            {editing ? (
              <>
                <label>名称<input className="field field-wide" value={draft.title} onChange={(event) => setDraft({ ...draft, title: event.target.value })} /></label>
                <label>目标<input className="field field-wide" value={draft.summary} onChange={(event) => setDraft({ ...draft, summary: event.target.value })} /></label>
                <label>分类<input className="field field-wide" value={draft.category} onChange={(event) => setDraft({ ...draft, category: event.target.value })} /></label>
                <label>步骤（每行一条命令或说明）<textarea className="runbook-editor" value={draft.steps.join("\n")} onChange={(event) => setDraft({ ...draft, steps: event.target.value.split(/\r?\n/) })} /></label>
                <label>完成标准（每行一条）<textarea className="runbook-editor" value={draft.success.join("\n")} onChange={(event) => setDraft({ ...draft, success: event.target.value.split(/\r?\n/) })} /></label>
                <p className="form-hint">参数字段可在步骤中使用 {"{{name}}"}，保存后会在 Runbook 列表中持久化。</p>
                <div className="form-actions"><button className="connect-button" type="button" disabled={!draft.title.trim() || !draft.summary.trim() || !draft.steps.some(Boolean) || !draft.success.some(Boolean)} onClick={() => void save()}>保存</button><button className="ghost-button" type="button" onClick={() => setEditing(false)}>取消</button></div>
              </>
            ) : (
            <>
            <div>
              <h3>{selected.title}</h3>
              <p className="form-hint">{selected.summary}</p>
            </div>
            {selected.fields.map((field) => (
              <label key={field.id}>
                {field.label}
                <input
                  className="field field-wide"
                  value={values[field.id] ?? ""}
                  onChange={(event) =>
                    setValues((current) => ({ ...current, [field.id]: event.target.value }))
                  }
                  placeholder={field.placeholder}
                  spellCheck={false}
                />
              </label>
            ))}
            <div>
              <span className="runbook-section-title">执行步骤</span>
              <ol className="runbook-steps">
                {selected.steps.map((step) => (
                  <li key={step}>{step.replace(/\{\{[^}]+\}\}/g, "填写参数")}</li>
                ))}
              </ol>
            </div>
            <p className="form-hint">
              选择后只会生成聊天草稿，不会立即执行；发送前仍可修改内容和执行策略。
            </p>
            <div className="form-actions">
              <button
                className="connect-button"
                type="button"
                disabled={!canUse}
                onClick={() => onUse(buildRunbookPrompt(selected, values))}
              >
                填入对话
              </button>
              <button className="ghost-button" type="button" onClick={onClose}>
                取消
              </button>
              {isCustom && <><button className="ghost-button" type="button" onClick={() => { setDraft(selected as CustomRunbook); setEditing(true); }}>编辑</button><button className="danger-button" type="button" onClick={() => void remove()}>删除</button></>}
            </div>
            </>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
