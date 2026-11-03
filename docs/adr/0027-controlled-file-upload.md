# ADR 0027: Controlled File Upload

## Context

The file pane could browse, download, edit, rename, and delete remote entries, but transferring a local artifact still required leaving the product and using a separate SCP client or shell command. Unbounded upload would make accidental large transfers, path traversal, overwrites, and link traversal too easy.

## Decision

Add a single-file upload action bound to the active terminal Session and `files` ticket. The destination is the currently displayed remote directory. The backend accepts one file up to 4 MiB, requires a single path-segment filename, rejects non-directories and links, and refuses an existing target. Bytes are written to a random temporary file in the destination directory and atomically renamed into place; the temporary file is removed on failure. Successful uploads write a `file_upload` event to the existing audit log.

The UI uses the platform file picker exposed by the webview, asks for explicit confirmation, shows upload progress state, refreshes the current directory, and reports failures inline. There is no batch upload, overwrite mode, directory upload, or AI file-write tool.

## Consequences

- Small local artifacts can be placed on the connected host without switching tools.
- Existing files cannot be overwritten accidentally; users must rename or delete explicitly first.
- Files above 4 MiB, links, invalid names, and non-directory targets remain outside this controlled surface.

## Verification

- `npm run build`
- `mvn -Dtest=FileServiceSftpTest test`
- `mvn test`
