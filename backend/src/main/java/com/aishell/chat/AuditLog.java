package com.aishell.chat;

import com.aishell.terminal.AiCommandResult;
import com.aishell.terminal.UserCommandListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

  public void recordFileWrite(String session, String path, long size) {
    Map<String, Object> entry = baseEntry("file_write", "user", session, path);
    entry.put("path", path);
    entry.put("size", size);
    append(entry);
  }

  public void recordFileDownload(String session, String path, long size) {
    Map<String, Object> entry = baseEntry("file_download", "user", session, path);
    entry.put("path", path);
    entry.put("size", size);
    append(entry);
  }

  /** Reads recent command records for the currently connected terminal session. */
  public History history(String session, String query, int limit) {
    String needle = query == null ? "" : query.trim().toLowerCase();
    List<Map<String, Object>> matches = new ArrayList<>();
    synchronized (lock) {
      for (int index = maxFiles; index >= 0; index--) {
        Path candidate = index == 0 ? file : sibling(index);
        if (!Files.isRegularFile(candidate)) {
          continue;
        }
        try {
          for (String line : Files.readAllLines(candidate)) {
            if (line.isBlank()) {
              continue;
            }
            Map<?, ?> parsed;
            try {
              parsed = json.readValue(line, Map.class);
            } catch (RuntimeException e) {
              log.debug("ignoring malformed audit line in {}", candidate);
              continue;
            }
            if (!"command".equals(parsed.get("kind"))
                || !session.equals(String.valueOf(parsed.get("session")))) {
              continue;
            }
            String command = String.valueOf(parsed.containsKey("command") ? parsed.get("command") : "");
            String cwd = String.valueOf(parsed.containsKey("cwd") ? parsed.get("cwd") : "");
            if (!needle.isEmpty()
                && !command.toLowerCase().contains(needle)
                && !cwd.toLowerCase().contains(needle)) {
              continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            parsed.forEach((key, value) -> copy.put(String.valueOf(key), value));
            matches.add(copy);
          }
        } catch (IOException e) {
          log.debug("failed to read audit history from {}", candidate, e);
        }
      }
    }
    Collections.reverse(matches);
    boolean truncated = matches.size() > limit;
    if (truncated) {
      matches = new ArrayList<>(matches.subList(0, limit));
    }
    return new History(matches, truncated);
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

  public record History(List<Map<String, Object>> entries, boolean truncated) {}
}
