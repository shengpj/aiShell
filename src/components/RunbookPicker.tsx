import { useEffect, useMemo, useState } from "react";
import { buildRunbookPrompt, RUNBOOKS } from "../runbooks";

interface RunbookPickerProps {
  onClose: () => void;
  onUse: (prompt: string) => void;
}

export default function RunbookPicker({ onClose, onUse }: RunbookPickerProps) {
  const [selectedId, setSelectedId] = useState(RUNBOOKS[0]?.id ?? "");
  const [values, setValues] = useState<Record<string, string>>({});
  const selected = useMemo(
    () => RUNBOOKS.find((runbook) => runbook.id === selectedId) ?? RUNBOOKS[0],
    [selectedId],
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

  if (!selected) {
    return null;
  }

  const canUse = selected.fields.every((field) => values[field.id]?.trim());

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
            {RUNBOOKS.map((runbook) => (
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
          </div>
          <div className="runbook-detail">
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
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
