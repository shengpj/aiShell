package com.aishell.chat;

import com.aishell.terminal.AiCommandResult;
import com.aishell.terminal.UserCommandListener;
import java.io.IOException;
import java.io.BufferedReader;
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
import java.util.function.Consumer;
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

  public void recordFileRename(String session, String from, String to) {
    Map<String, Object> entry = baseEntry("file_rename", "user", session, from);
    entry.put("path", from);
    entry.put("to", to);
    append(entry);
  }

  public void recordFileDelete(String session, String path, String type) {
    Map<String, Object> entry = baseEntry("file_delete", "user", session, path);
    entry.put("path", path);
    entry.put("fileType", type);
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

  /** Searches every live and rotated audit file, newest records first. */
  public AuditPage search(AuditFilter filter) {
    int page = Math.max(0, filter.page());
    int size = Math.max(1, Math.min(filter.size(), 200));
    int keep = Math.min(10_000, (page + 1) * size);
    List<Map<String, Object>> newest = new ArrayList<>();
    long total = 0;
    synchronized (lock) {
      forEachMatch(filter, entry -> {
        // A bounded tail keeps paging usable even when the audit directory is large.
        newest.add(entry);
        if (newest.size() > keep) {
          newest.removeFirst();
        }
      });
      total = countMatches(filter);
    }
    Collections.reverse(newest);
    int from = Math.min(page * size, newest.size());
    int to = Math.min(from + size, newest.size());
    return new AuditPage(newest.subList(from, to), total, page, size, from + size < total);
  }

  /** Writes matching records without materializing the complete audit history. */
  public void writeMatches(AuditFilter filter, Consumer<Map<String, Object>> consumer) {
    synchronized (lock) {
      forEachMatch(filter, consumer);
    }
  }

  private long countMatches(AuditFilter filter) {
    final long[] count = {0};
    forEachMatch(filter, ignored -> count[0]++);
    return count[0];
  }

  private void forEachMatch(AuditFilter filter, Consumer<Map<String, Object>> consumer) {
    for (int index = maxFiles; index >= 0; index--) {
      Path candidate = index == 0 ? file : sibling(index);
      if (!Files.isRegularFile(candidate)) {
        continue;
      }
      try (BufferedReader reader = Files.newBufferedReader(candidate)) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.isBlank()) continue;
          try {
            Map<?, ?> parsed = json.readValue(line, Map.class);
            if (!matches(parsed, filter)) continue;
            Map<String, Object> copy = new LinkedHashMap<>();
            parsed.forEach((key, value) -> copy.put(String.valueOf(key), value));
            copy.put("source", candidate.getFileName().toString());
            consumer.accept(copy);
          } catch (RuntimeException e) {
            log.debug("ignoring malformed audit line in {}", candidate);
          }
        }
      } catch (IOException e) {
        log.debug("failed to read audit records from {}", candidate, e);
      }
    }
  }

  private static boolean matches(Map<?, ?> entry, AuditFilter filter) {
    String at = text(entry, "at");
    if (filter.from() != null && (at.isBlank() || Instant.parse(at).isBefore(filter.from()))) return false;
    if (filter.to() != null && (at.isBlank() || Instant.parse(at).isAfter(filter.to()))) return false;
    if (notBlank(filter.session()) && !filter.session().equals(text(entry, "session"))) return false;
    if (notBlank(filter.initiator()) && !filter.initiator().equals(text(entry, "initiator"))) return false;
    if (notBlank(filter.kind()) && !filter.kind().equals(text(entry, "kind"))) return false;
    if (notBlank(filter.status()) && !filter.status().equals(text(entry, "status"))) return false;
    if (notBlank(filter.query())) {
      String needle = filter.query().trim().toLowerCase();
      String haystack = entry.values().stream().map(String::valueOf).reduce("", (a, b) -> a + " " + b).toLowerCase();
      if (!haystack.contains(needle)) return false;
    }
    return true;
  }

  private static String text(Map<?, ?> entry, String key) {
    Object value = entry.get(key);
    return value == null ? "" : String.valueOf(value);
  }

  private static boolean notBlank(String value) { return value != null && !value.isBlank(); }

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

  public record AuditFilter(
      Instant from, Instant to, String session, String initiator, String kind, String status,
      String query, int page, int size) {}

  public record AuditPage(
      List<Map<String, Object>> entries, long total, int page, int size, boolean hasMore) {}
}
