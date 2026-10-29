package com.aishell.chat;

import com.aishell.terminal.AiCommandResult;
import com.aishell.terminal.UserCommandListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Append-only JSONL audit trail for AI-initiated and shell-hook user commands (ADR 0002, 0010). */
@Component
public class AuditLog implements UserCommandListener {

  private static final Logger log = LoggerFactory.getLogger(AuditLog.class);
  private static final int OUTPUT_LIMIT = 4_000;

  private final Path file;
  private final ObjectMapper json;
  private final long maxBytes;
  private final int maxFiles;
  private final Object lock = new Object();

  public AuditLog(ObjectMapper json, String dataDir) throws IOException {
    this(json, dataDir, 10L * 1024 * 1024, 5);
  }

  @Autowired
  public AuditLog(
      ObjectMapper json,
      @Value("${ai-shell.data.dir:${user.home}/.aishell}") String dataDir,
      @Value("${ai-shell.audit.max-bytes:10485760}") long maxBytes,
      @Value("${ai-shell.audit.max-files:5}") int maxFiles)
      throws IOException {
    Path dir = Path.of(dataDir);
    Files.createDirectories(dir);
    this.file = dir.resolve("audit.jsonl");
    this.json = json;
    this.maxBytes = maxBytes;
    this.maxFiles = maxFiles;
  }

  public void recordCommand(String session, String command, AiCommandResult result) {
    Map<String, Object> entry = baseEntry("command", "ai", session, command);
    entry.put("status", result.status().name().toLowerCase());
    entry.put("exitCode", result.exitCode());
    entry.put("durationMs", result.durationMs());
    if (result.message() != null) {
      entry.put("message", result.message());
    }
    entry.put("output", truncate(result.output()));
    append(entry);
  }

  public void recordRefusal(String session, String command, String reason) {
    Map<String, Object> entry = baseEntry("refused", "ai", session, command);
    entry.put("reason", reason);
    append(entry);
  }

  /** A command the user typed in a shell with the audit hook (ADR 0010). */
  @Override
  public void onUserCommand(String session, String command, int exitCode, String cwd) {
    Map<String, Object> entry = baseEntry("command", "user", session, command);
    entry.put("exitCode", exitCode);
    entry.put("cwd", cwd);
    append(entry);
  }

  private static Map<String, Object> baseEntry(
      String kind, String initiator, String session, String command) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("at", Instant.now().toString());
    entry.put("kind", kind);
    entry.put("initiator", initiator);
    entry.put("session", session);
    entry.put("command", command);
    return entry;
  }

  private void append(Map<String, Object> entry) {
    try {
      String line = json.writeValueAsString(entry) + "\n";
      synchronized (lock) {
        rotateIfNeeded();
        Files.writeString(
            file,
            line,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
            StandardOpenOption.WRITE);
      }
    } catch (IOException | RuntimeException e) {
      log.error("failed to append audit record to {}", file, e);
    }
  }

  /** Size-based rotation: {@code audit.jsonl} → {@code audit.jsonl.1} → … → {@code .maxFiles}. */
  private void rotateIfNeeded() throws IOException {
    if (maxBytes <= 0 || !Files.exists(file) || Files.size(file) < maxBytes) {
      return;
    }
    if (maxFiles <= 0) {
      Files.deleteIfExists(file);
      return;
    }
    Files.deleteIfExists(sibling(maxFiles));
    for (int index = maxFiles - 1; index >= 1; index--) {
      Path older = sibling(index);
      if (Files.exists(older)) {
        Files.move(older, sibling(index + 1), StandardCopyOption.REPLACE_EXISTING);
      }
    }
    Files.move(file, sibling(1), StandardCopyOption.REPLACE_EXISTING);
    log.info("rotated {} at {} bytes (keeping {} files)", file, maxBytes, maxFiles);
  }

  private Path sibling(int index) {
    return file.resolveSibling(file.getFileName() + "." + index);
  }

  private static String truncate(String text) {
    if (text == null || text.length() <= OUTPUT_LIMIT) {
      return text;
    }
    return text.substring(0, OUTPUT_LIMIT) + "… (truncated)";
  }
}
