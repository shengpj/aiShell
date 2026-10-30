import { confirm as confirmDialog } from "@tauri-apps/plugin-dialog";
import { isTauri } from "./backend";

/**
 * WebView2 does not implement blocking `window.confirm` inside Tauri (it resolves immediately),
 * so destructive actions must use the native dialog plugin. If that dialog fails for any reason,
 * deny the action instead of deleting without confirmation.
 */
export async function confirmAction(
  message: string,
  title: string,
  okLabel: string,
): Promise<boolean> {
  if (!isTauri()) {
    return window.confirm(message);
  }
  try {
    return await confirmDialog(message, {
      title,
      kind: "warning",
      okLabel,
      cancelLabel: "取消",
    });
  } catch (cause) {
    console.error("confirmation dialog failed", cause);
    return false;
  }
}

export function confirmDestructive(message: string, title: string): Promise<boolean> {
  return confirmAction(message, title, "删除");
}
