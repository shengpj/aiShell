const DEFAULT_CONTEXT_LIMIT = 24 * 1024;

export function limitAiContext(value: string, limit = DEFAULT_CONTEXT_LIMIT): {
  text: string;
  truncated: boolean;
} {
  const text = value.trim();
  if (text.length <= limit) {
    return { text, truncated: false };
  }
  return { text: text.slice(0, limit), truncated: true };
}

export function contextSuffix(truncated: boolean): string {
  return truncated ? "\n\n[上下文已截断，仅保留前 24 KB]" : "";
}
