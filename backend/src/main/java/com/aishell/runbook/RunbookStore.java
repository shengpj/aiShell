package com.aishell.runbook;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Persistent user-owned runbooks. Built-in runbooks remain a frontend baseline. */
@Component
public class RunbookStore {

  public record Field(String id, String label, String placeholder) {}

  public record Runbook(
      String id,
      String title,
      String summary,
      String category,
      List<Field> fields,
      List<String> steps,
      List<String> success,
      int version,
      String createdAt,
      String updatedAt) {}

  private final String url;
  private final ObjectMapper json;

  public RunbookStore(
      ObjectMapper json,
      @Value("${ai-shell.data.dir:${user.home}/.aishell}") String dataDir) {
    try {
      Path dir = Path.of(dataDir);
      Files.createDirectories(dir);
      this.url = "jdbc:sqlite:" + dir.resolve("ai-shell.db").toAbsolutePath() + "?busy_timeout=3000";
      this.json = json;
      initSchema();
    } catch (Exception e) {
      throw new IllegalStateException("failed to open the runbook store", e);
    }
  }

  public synchronized List<Runbook> list() {
    return query("SELECT * FROM runbook WHERE deleted_at IS NULL ORDER BY updated_at DESC, title, id");
  }

  public synchronized Optional<Runbook> find(String id) {
    return query("SELECT * FROM runbook WHERE id = ? AND deleted_at IS NULL", id).stream().findFirst();
  }

  public synchronized Runbook insert(String title, String summary, String category,
      List<Field> fields, List<String> steps, List<String> success) {
    String id = UUID.randomUUID().toString();
    String now = Instant.now().toString();
    update("INSERT INTO runbook(id,title,summary,category,fields_json,steps_json,success_json,version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
        id, title, summary, category, encode(fields), encode(steps), encode(success), 1, now, now);
    return new Runbook(id, title, summary, category, List.copyOf(fields), List.copyOf(steps), List.copyOf(success), 1, now, now);
  }

  public synchronized Optional<Runbook> update(String id, String title, String summary, String category,
      List<Field> fields, List<String> steps, List<String> success, int expectedVersion) {
    String now = Instant.now().toString();
    int changed = update("UPDATE runbook SET title=?,summary=?,category=?,fields_json=?,steps_json=?,success_json=?,version=version+1,updated_at=? WHERE id=? AND deleted_at IS NULL AND version=?",
        title, summary, category, encode(fields), encode(steps), encode(success), now, id, expectedVersion);
    return changed == 0 ? Optional.empty() : find(id);
  }

  public synchronized boolean delete(String id) {
    return update("UPDATE runbook SET deleted_at=?,updated_at=? WHERE id=? AND deleted_at IS NULL",
        Instant.now().toString(), Instant.now().toString(), id) > 0;
  }

  private List<Runbook> query(String sql, Object... args) {
    List<Runbook> result = new ArrayList<>();
    try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, args);
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          result.add(new Runbook(rs.getString("id"), rs.getString("title"), rs.getString("summary"),
              rs.getString("category"), decodeFields(rs.getString("fields_json")),
              decodeStrings(rs.getString("steps_json")), decodeStrings(rs.getString("success_json")),
              rs.getInt("version"), rs.getString("created_at"), rs.getString("updated_at")));
        }
      }
      return result;
    } catch (SQLException e) {
      throw new IllegalStateException("failed to query runbooks", e);
    }
  }

  private int update(String sql, Object... args) {
    try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, args);
      return statement.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("failed to update runbooks", e);
    }
  }

  private static void bind(PreparedStatement statement, Object[] args) throws SQLException {
    for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
  }

  private String encode(Object value) {
    try { return json.writeValueAsString(value); }
    catch (RuntimeException e) { throw new IllegalStateException("failed to encode runbook", e); }
  }

  private List<Field> decodeFields(String value) {
    try { return json.readValue(value, new TypeReference<List<Field>>() {}); }
    catch (RuntimeException e) { throw new IllegalStateException("failed to decode runbook fields", e); }
  }

  private List<String> decodeStrings(String value) {
    try { return json.readValue(value, new TypeReference<List<String>>() {}); }
    catch (RuntimeException e) { throw new IllegalStateException("failed to decode runbook steps", e); }
  }

  private void initSchema() throws SQLException {
    try (Connection connection = open(); Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE IF NOT EXISTS runbook(id TEXT PRIMARY KEY,title TEXT NOT NULL,summary TEXT NOT NULL,category TEXT NOT NULL,fields_json TEXT NOT NULL,steps_json TEXT NOT NULL,success_json TEXT NOT NULL,version INTEGER NOT NULL,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,deleted_at TEXT)");
    }
  }

  private Connection open() throws SQLException { return DriverManager.getConnection(url); }
}
