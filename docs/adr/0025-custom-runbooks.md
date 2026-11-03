# ADR 0025: Custom Runbooks

## Context

The first Runbook release shipped three read-only workflows as frontend constants. Users could fill parameters and generate an AI draft, but they could not adapt the workflow to their own services or keep a reusable procedure.

## Decision

Store user-created Runbooks in the existing SQLite data directory. Each record contains title, summary, category, parameter metadata, ordered steps, completion criteria, conditional branches, timestamps, and an integer version. Every saved version is also an immutable snapshot in `runbook_revision`. Updates use the version supplied by the editor; a stale version returns HTTP 409 instead of silently overwriting another edit. Rollback creates a new version from an older snapshot, so no history is discarded. Deletes are soft deletes so future audit and history features can preserve references.

Expose CRUD under `/api/runbooks` using the existing ticket authentication with purpose `runbooks`. Add `/api/runbooks/{id}/revisions` for history and `/api/runbooks/{id}/rollback` for version restoration. The UI combines the built-in read-only Runbooks with persisted custom Runbooks and supports create, edit, delete, history, rollback, conditional branches, and draft generation. Generated drafts tell the AI to evaluate a condition from observed facts, execute only the matching branch, and record why a branch was skipped when the condition is unknown. A custom Runbook still only fills the chat composer; AI execution policy, command guardrails, approvals, timeouts, and audit behavior remain the same.

## Consequences

- Teams can maintain host-specific diagnostic procedures without changing the application bundle.
- Built-in procedures remain available even if the backend is unavailable.
- The current editor stores steps and completion criteria as lines; richer field editing, branching, execution history, and shared sync remain follow-up work.

## Verification

- `npm run build`
- `mvn -Dtest=RunbookStoreTest test`
- `mvn -DskipTests package`
