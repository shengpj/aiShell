package com.aishell.file;

import com.aishell.chat.AuditLog;
import com.aishell.terminal.TerminalSessions;
import com.jcraft.jsch.Session;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Bounded file browsing plus explicitly requested, audited text edits and downloads. */
@RestController
@RequestMapping("/api/files")
public class FileController {

  private final FileService files;
  private final TerminalSessions terminals;
  private final AuditLog audit;

  public FileController(FileService files, TerminalSessions terminals, AuditLog audit) {
    this.files = files;
    this.terminals = terminals;
    this.audit = audit;
  }

  @GetMapping
  public FileService.FileListing list(
      @RequestParam("session") String session,
      @RequestParam(value = "path", defaultValue = ".") String path) {
    return files.list(liveSession(session), path);
  }

  @GetMapping("/preview")
  public FileService.FilePreview preview(
      @RequestParam("session") String session, @RequestParam("path") String path) {
    return files.preview(liveSession(session), path);
  }

  @PutMapping("/content")
  public Map<String, Object> write(
      @RequestBody(required = false) FileWriteRequest request) {
    if (request == null || request.session() == null || request.session().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "session is required");
    }
    FileService.FileWriteResult result =
        files.writeText(
            liveSession(request.session()),
            request.path(),
            request.content(),
            request.expectedModifiedAt());
    audit.recordFileWrite(request.session(), result.path(), result.size());
    return Map.of(
        "path", result.path(), "size", result.size(), "modifiedAt", result.modifiedAt());
  }

  @GetMapping("/download")
  public ResponseEntity<byte[]> download(
      @RequestParam("session") String session, @RequestParam("path") String path) {
    FileService.FileDownload result = files.download(liveSession(session), path);
    audit.recordFileDownload(session, result.path(), result.data().length);
    MediaType mediaType;
    try {
      mediaType = MediaType.parseMediaType(result.mime());
    } catch (IllegalArgumentException e) {
      mediaType = MediaType.APPLICATION_OCTET_STREAM;
    }
    return ResponseEntity.ok()
        .contentType(mediaType)
        .contentLength(result.data().length)
        .header(
            "Content-Disposition",
            ContentDisposition.attachment().filename(result.name()).build().toString())
        .body(result.data());
  }

  public record FileWriteRequest(
      String session, String path, String content, Long expectedModifiedAt) {}

  private Session liveSession(String session) {
    return terminals
        .findSshSession(session)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.CONFLICT, "terminal session is not connected"));
  }
}
