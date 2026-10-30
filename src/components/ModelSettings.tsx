import { useEffect, useState } from "react";
import { confirmDestructive } from "../api/confirm";
import {
  createModel,
  deleteModel,
  listModels,
  updateModel,
  type ModelInput,
  type ModelView,
} from "../api/models";

interface ModelForm {
  /** Null while adding a model. */
  id: string | null;
  name: string;
  baseUrl: string;
  model: string;
  apiKey: string;
  hasApiKey: boolean;
}

interface ModelSettingsProps {
  onClose: () => void;
  /** Called after any change so the chat pane can reload the selector. */
  onChanged: () => void;
}

const EMPTY_FORM: ModelForm = {
  id: null,
  name: "",
  baseUrl: "",
  model: "",
  apiKey: "",
  hasApiKey: false,
};

function message(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause);
}

export default function ModelSettings({ onClose, onChanged }: ModelSettingsProps) {
  const [models, setModels] = useState<ModelView[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [form, setForm] = useState<ModelForm | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const refresh = async () => {
    setModels(await listModels());
    setLoaded(true);
  };

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const list = await listModels();
        if (!cancelled) {
          setModels(list);
          setLoaded(true);
          setError(null);
        }
      } catch (cause) {
        if (!cancelled) {
          setError(message(cause));
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    const onKeyDown = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  const openEdit = (model: ModelView) => {
    setForm({
      id: model.id,
      name: model.name,
      baseUrl: model.baseUrl,
      model: model.model,
      apiKey: "",
      hasApiKey: model.hasApiKey,
    });
    setError(null);
  };

  const save = async () => {
    if (!form) {
      return;
    }
    const input: ModelInput = {
      name: form.name.trim(),
      baseUrl: form.baseUrl.trim(),
      model: form.model.trim(),
    };
    if (!input.name || !input.baseUrl || !input.model) {
      return;
    }
    if (form.apiKey.trim()) {
      input.apiKey = form.apiKey.trim();
    }
    setBusy(true);
    try {
      if (form.id) {
        await updateModel(form.id, input);
      } else {
        await createModel(input);
      }
      await refresh();
      setForm(null);
      setError(null);
      onChanged();
    } catch (cause) {
      setError(message(cause));
    } finally {
      setBusy(false);
    }
  };

  const remove = async (model: ModelView) => {
    if (!(await confirmDestructive(`删除模型 ${model.name}？已保存的 API Key 也会一并删除。`, "删除模型"))) {
      return;
    }
    try {
      await deleteModel(model.id);
      if (form?.id === model.id) {
        setForm(null);
      }
      await refresh();
      setError(null);
      onChanged();
    } catch (cause) {
      setError(message(cause));
    }
  };

  const needsApiKey = form !== null && !form.id && !form.apiKey.trim();

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
      <div className="modal" role="dialog" aria-modal="true" aria-label="模型设置">
        <div className="modal-head">
          <span>模型设置</span>
          <button className="icon-button" type="button" onClick={onClose} aria-label="关闭">
            ×
          </button>
        </div>
        <div className="modal-body">
          {error && <p className="conn-error">{error}</p>}

          {form ? (
            <form
              className="conn-form"
              onSubmit={(event) => {
                event.preventDefault();
                void save();
              }}
            >
              <span className="form-title">{form.id ? "编辑模型" : "添加模型"}</span>
              <label>
                名称
                <input
                  className="field field-wide"
                  value={form.name}
                  onChange={(event) => setForm({ ...form, name: event.target.value })}
                  placeholder="给这个模型起个名字"
                  spellCheck={false}
                />
              </label>
              <label>
                Base URL
                <input
                  className="field field-wide"
                  value={form.baseUrl}
                  onChange={(event) => setForm({ ...form, baseUrl: event.target.value })}
                  placeholder="服务商提供的接口地址"
                  spellCheck={false}
                />
              </label>
              <label>
                模型
                <input
                  className="field field-wide"
                  value={form.model}
                  onChange={(event) => setForm({ ...form, model: event.target.value })}
                  placeholder="服务商给出的模型名"
                  spellCheck={false}
                />
              </label>
              <label>
                API Key
                <input
                  className="field field-wide"
                  type="password"
                  value={form.apiKey}
                  onChange={(event) => setForm({ ...form, apiKey: event.target.value })}
                  placeholder={form.hasApiKey ? "已保存，留空保持不变" : "粘贴服务商提供的 API Key"}
                  autoComplete="new-password"
                />
              </label>
              <p className="form-hint">
                API Key 用 AES-GCM 加密后保存在本机，只在请求模型时使用，不会显示在界面上。
              </p>
              <div className="form-actions">
                <button className="connect-button" type="submit" disabled={busy || needsApiKey}>
                  {form.id ? "保存" : "添加"}
                </button>
                <button className="ghost-button" type="button" onClick={() => setForm(null)}>
                  取消
                </button>
              </div>
            </form>
          ) : (
            <>
              <button className="conn-add" type="button" onClick={() => setForm({ ...EMPTY_FORM })}>
                <span className="conn-add-plus">+</span>
                添加模型
              </button>
              {loaded && models.length === 0 && (
                <p className="empty-hint">还没有模型，添加后即可在聊天中选择。</p>
              )}
              <div className="model-list">
                {models.map((model) => (
                  <div className="model-row" key={model.id}>
                    <div className="model-row-main">
                      <span className="model-row-name">{model.name}</span>
                      <span className="model-row-meta">
                        {model.model} · {model.baseUrl}
                      </span>
                    </div>
                    <button className="mini" type="button" onClick={() => openEdit(model)}>
                      编辑
                    </button>
                    <button
                      className="mini mini-danger"
                      type="button"
                      onClick={() => void remove(model)}
                    >
                      删除
                    </button>
                  </div>
                ))}
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
