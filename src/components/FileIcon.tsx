import type { ReactNode } from "react";
import type { FileType } from "../api/files";

type IconKind =
  | "folder"
  | "folder-open"
  | "link"
  | "image"
  | "archive"
  | "code"
  | "script"
  | "config"
  | "text"
  | "file";

const IMAGE_EXTENSIONS = new Set(["png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico", "tif", "tiff"]);
const ARCHIVE_EXTENSIONS = new Set([
  "zip", "tar", "gz", "tgz", "bz2", "xz", "zst", "7z", "rar", "jar", "war", "deb", "rpm",
]);
const SCRIPT_EXTENSIONS = new Set(["sh", "bash", "zsh", "ksh", "ps1", "bat", "cmd"]);
const CODE_EXTENSIONS = new Set([
  "js", "jsx", "mjs", "cjs", "ts", "tsx", "java", "kt", "kts", "scala", "go", "rs", "c", "h",
  "cc", "cpp", "hpp", "cs", "rb", "php", "pl", "lua", "sql", "html", "htm", "css", "scss",
  "sass", "less", "vue", "svelte", "py", "swift",
]);
const CONFIG_EXTENSIONS = new Set([
  "json", "jsonc", "xml", "yml", "yaml", "toml", "ini", "cfg", "conf", "properties", "env",
  "service", "desktop", "repo", "list",
]);
const TEXT_EXTENSIONS = new Set(["txt", "md", "markdown", "log", "csv", "tsv", "rst", "adoc"]);

export function iconKind(name: string, type: FileType): IconKind {
  if (type === "dir") {
    return "folder";
  }
  if (type === "link") {
    return "link";
  }
  const dot = name.lastIndexOf(".");
  if (dot < 0) {
    return "file";
  }
  if (dot === 0) {
    return "config";
  }
  const ext = name.slice(dot + 1).toLowerCase();
  if (IMAGE_EXTENSIONS.has(ext)) {
    return "image";
  }
  if (ARCHIVE_EXTENSIONS.has(ext)) {
    return "archive";
  }
  if (SCRIPT_EXTENSIONS.has(ext)) {
    return "script";
  }
  if (CODE_EXTENSIONS.has(ext)) {
    return "code";
  }
  if (CONFIG_EXTENSIONS.has(ext)) {
    return "config";
  }
  if (TEXT_EXTENSIONS.has(ext)) {
    return "text";
  }
  return "file";
}

const SHAPES: Record<IconKind, ReactNode> = {
  folder: (
    <path
      d="M1.9 3.6h3.7l1.3 1.7h7.2c.5 0 .9.4.9.9v6.1c0 .5-.4.9-.9.9H2.1a.9.9 0 0 1-.9-.9V4.5c0-.5.4-.9.9-.9Z"
      fill="currentColor"
      stroke="none"
    />
  ),
  "folder-open": (
    <>
      <path
        d="M1.2 13.1V4.5c0-.5.4-.9.9-.9h3.7l1.3 1.7h6c.5 0 .9.4.9.9v1.4H3.2L1.2 13.1Z"
        fill="currentColor"
        stroke="none"
        opacity="0.55"
      />
      <path d="M3.4 14 5.3 7.9h9.4l-1.9 6.1Z" fill="currentColor" stroke="none" />
    </>
  ),
  link: <path d="M3.2 12.8 12.4 3.6M12.4 3.6H7.4M12.4 3.6v5" />,
  image: (
    <>
      <rect x="2.3" y="3.2" width="11.4" height="9.6" rx="1.1" />
      <circle cx="5.7" cy="6.3" r="1" />
      <path d="m2.8 11.6 3.1-3 2.3 2.2 1.7-1.4 3.3 2.9" />
    </>
  ),
  archive: (
    <>
      <rect x="2.4" y="2.8" width="11.2" height="10.4" rx="1.1" />
      <path d="M2.4 6.1h11.2M7 2.8v3.3M9 2.8v3.3" />
      <rect x="6.7" y="7.9" width="2.6" height="2.6" rx=".5" />
    </>
  ),
  code: <path d="M6 5.2 3.2 8 6 10.8M10 5.2 12.8 8 10 10.8" />,
  script: (
    <>
      <rect x="2.2" y="3" width="11.6" height="10" rx="1.1" />
      <path d="m5.2 6.4 1.9 1.6-1.9 1.6M8.7 9.9h3" />
    </>
  ),
  config: (
    <>
      <path d="M2.6 5h2.8M9.2 5h4.2M2.6 8h5.9M12.1 8h1.3M2.6 11h2.8M9.2 11h4.2" />
      <path d="M7.2 3.7v2.6M10.3 6.7v2.6M7.2 9.7v2.6" />
    </>
  ),
  text: (
    <>
      <path d="M4.2 1.9h4.5l3.5 3.4v8.8H4.2z" />
      <path d="M8.7 1.9v3.4h3.5" />
      <path d="M6 8.3h4.6M6 10.3h4.6M6 12.2h2.8" />
    </>
  ),
  file: (
    <>
      <path d="M4.2 1.9h4.5l3.5 3.4v8.8H4.2z" />
      <path d="M8.7 1.9v3.4h3.5" />
    </>
  ),
};

export default function FileIcon({ kind, className }: { kind: IconKind; className?: string }) {
  return (
    <svg
      className={className ? `file-icon icon-${kind} ${className}` : `file-icon icon-${kind}`}
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.2}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {SHAPES[kind]}
    </svg>
  );
}
