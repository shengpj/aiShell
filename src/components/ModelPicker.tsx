import { useEffect, useRef, useState, type KeyboardEvent } from "react";
import type { ModelView } from "../api/models";

type Props = {
  models: ModelView[];
  value: string;
  onChange: (id: string) => void;
  onManage: () => void;
  disabled?: boolean;
  loading?: boolean;
};

export default function ModelPicker({
  models,
  value,
  onChange,
  onManage,
  disabled = false,
  loading = false,
}: Props) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const itemRefs = useRef<(HTMLButtonElement | null)[]>([]);
  const current = models.find((model) => model.id === value) ?? models[0];

  useEffect(() => {
    if (!open) {
      return;
    }
    const frame = requestAnimationFrame(() => {
      const index = Math.max(
        0,
        models.findIndex((model) => model.id === value),
      );
      itemRefs.current[index]?.focus();
    });
    const closeOnOutside = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener("pointerdown", closeOnOutside);
    return () => {
      cancelAnimationFrame(frame);
      document.removeEventListener("pointerdown", closeOnOutside);
    };
  }, [open, models, value]);

  const close = (refocus = true) => {
    setOpen(false);
    if (refocus) {
      triggerRef.current?.focus();
    }
  };

  const onMenuKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const items = itemRefs.current.filter((item): item is HTMLButtonElement => item !== null);
    if (items.length === 0) {
      return;
    }
    const index = items.indexOf(document.activeElement as HTMLButtonElement);
    if (event.key === "ArrowDown") {
      event.preventDefault();
      items[(index + 1) % items.length].focus();
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      items[(index - 1 + items.length) % items.length].focus();
    } else if (event.key === "Home") {
      event.preventDefault();
      items[0].focus();
    } else if (event.key === "End") {
      event.preventDefault();
      items[items.length - 1].focus();
    } else if (event.key === "Escape") {
      event.preventDefault();
      close();
    } else if (event.key === "Tab") {
      setOpen(false);
    }
  };

  const label = current ? current.name : loading ? "加载中…" : "未配置模型";

  return (
    <div className="model-picker" ref={rootRef}>
      <button
        className="model-trigger"
        type="button"
        ref={triggerRef}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label="选择模型"
        title="这条消息使用的模型"
        disabled={disabled}
        onClick={() => setOpen((value) => !value)}
        onKeyDown={(event) => {
          if (!open && (event.key === "ArrowDown" || event.key === "ArrowUp")) {
            event.preventDefault();
            setOpen(true);
          }
        }}
      >
        <ModelGlyph />
        <span className="model-trigger-label">{label}</span>
        <ChevronGlyph />
      </button>

      {open && (
        <div
          className="model-menu"
          role="menu"
          aria-label="选择模型"
          onKeyDown={onMenuKeyDown}
        >
          {models.length === 0 ? (
            <div className="model-menu-empty">{loading ? "正在加载模型…" : "还没有模型档案"}</div>
          ) : (
            models.map((model, index) => {
              const selected = model.id === current?.id;
              return (
                <button
                  key={model.id}
                  ref={(node) => {
                    itemRefs.current[index] = node;
                  }}
                  className={selected ? "model-menu-item selected" : "model-menu-item"}
                  type="button"
                  role="menuitemradio"
                  aria-checked={selected}
                  tabIndex={-1}
                  onClick={() => {
                    onChange(model.id);
                    close();
                  }}
                >
                  <ModelGlyph className="model-menu-icon" />
                  <span className="model-menu-text">
                    <span className="model-menu-name">{model.name}</span>
                    <span className="model-menu-sub">{model.model}</span>
                  </span>
                  {selected && <CheckGlyph />}
                </button>
              );
            })
          )}

          <div className="model-menu-divider" role="separator" />

          <button
            ref={(node) => {
              itemRefs.current[models.length] = node;
            }}
            className="model-menu-item"
            type="button"
            role="menuitem"
            tabIndex={-1}
            onClick={() => {
              close(false);
              onManage();
            }}
          >
            <SettingsGlyph className="model-menu-icon" />
            <span className="model-menu-text">模型管理</span>
          </button>
        </div>
      )}
    </div>
  );
}

function ModelGlyph({ className }: { className?: string }) {
  return (
    <svg
      className={className ?? "model-trigger-icon"}
      viewBox="0 0 16 16"
      width="14"
      height="14"
      aria-hidden="true"
    >
      <path
        d="M8 1.4 9.55 6.45 14.6 8 9.55 9.55 8 14.6 6.45 9.55 1.4 8 6.45 6.45Z"
        fill="currentColor"
      />
    </svg>
  );
}

function ChevronGlyph() {
  return (
    <svg className="model-chevron" viewBox="0 0 16 16" width="12" height="12" aria-hidden="true">
      <path
        d="M4 6.2 8 10.2 12 6.2"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.5"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

function CheckGlyph() {
  return (
    <svg className="model-menu-check" viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
      <path
        d="M3.4 8.4 6.4 11.4 12.6 4.8"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

function SettingsGlyph({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
      <circle cx="8" cy="8" r="2.3" fill="none" stroke="currentColor" strokeWidth="1.4" />
      <path
        d="M8 1.5v1.9M8 12.6v1.9M1.5 8h1.9M12.6 8h1.9M3.4 3.4l1.35 1.35M11.25 11.25l1.35 1.35M12.6 3.4l-1.35 1.35M4.75 11.25 3.4 12.6"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.4"
        strokeLinecap="round"
      />
    </svg>
  );
}
