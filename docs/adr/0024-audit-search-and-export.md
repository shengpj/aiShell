# ADR 0024: Audit Search and Export

## Context

AI Shell already writes command, refusal, file-save, and file-download events to `audit.jsonl` and rotates the file by size. The command-history endpoint intentionally exposes only shell commands for one live terminal session, so it cannot serve operations review across event types or rotated files.

## Decision

Add `/api/audit` for paged, server-side filtering by time range, session, initiator, event kind, status, and keyword. The implementation reads the current audit file and retained rotations through the existing `AuditLog`; it does not add a second store. Each result carries its source file so operators can tell when a record came from a rotated archive.

Add `/api/audit/export?format=json|csv` with the same filters. Export uses a streaming response so large audit files are not assembled into one response object. The UI provides an audit center with filters, paged rows, full event JSON, and JSON/CSV download.

Audit records remain distinct by `kind`; file events are not converted into command-history rows.

## Consequences

- Operators can investigate a host or session across command and file activity.
- Rotation no longer hides older records from the UI while they are retained.
- Export is bounded by the configured rotation retention and currently includes the existing record fields as written.
- Sensitive-field redaction, retention policy controls, and archival cleanup remain follow-up work.

## Verification

- `npm run build`
- `mvn -Dtest=AuditLogTest test`
- `mvn -DskipTests package`
