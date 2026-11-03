# ADR 0026: Controlled File Rename and Delete

## Context

The file pane could browse, download, and edit existing text files, but routine lifecycle operations still required switching to the shell. Adding unrestricted SFTP mutation would make accidental cross-directory moves, recursive deletion, and link traversal too easy.

## Decision

Add audited rename and delete operations bound to the current terminal Session and `files` ticket. Rename must stay within the source directory, cannot overwrite an existing target, and refuses links. Delete refuses the root and links, removes regular files, and uses `rmdir` for directories so non-empty directories fail instead of being recursively removed.

The UI requires an explicit confirmation for each operation, refreshes the directory after success, and exposes the actions on each row. The backend records `file_rename` and `file_delete` events in the existing audit log.

## Consequences

- Common file cleanup and naming changes are available without leaving the file pane.
- The operation surface is deliberately narrower than arbitrary shell or recursive SFTP mutation.
- Upload, batch operations, diff, backup, and rollback remain follow-up work.

## Verification

- `npm run build`
- `mvn -Dtest=FileServiceSftpTest test`
- `mvn -DskipTests package`
