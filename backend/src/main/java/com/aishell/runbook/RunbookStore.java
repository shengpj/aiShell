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

  public record Revision(
      String runbookId,
      int version,
      String title,
      String summary,
      String category,
      List<Field> fields,
      List<String> steps,
      List<String> success,
      String createdAt) {}

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
    insertRevision(id, 1, title, summary, category, fields, steps, success, now);
    return new Runbook(id, title, summary, category, List.copyOf(fields), List.copyOf(steps), List.copyOf(success), 1, now, now);
  }

  public synchronized Optional<Runbook> update(String id, String title, String summary, String category,
      List<Field> fields, List<String> steps, List<String> success, int expectedVersion) {
    String now = Instant.now().toString();
    try (Connection connection = open()) {
      connection.setAutoCommit(false);
      try (PreparedStatement statement = connection.prepareStatement(
          "UPDATE runbook SET title=?,summary=?,category=?,fields_json=?,steps_json=?,success_json=?,version=version+1,updated_at=? WHERE id=? AND deleted_at IS NULL AND version=?")) {
        bind(statement, new Object[] {title, summary, category, encode(fields), encode(steps), encode(success), now, id, expectedVersion});
        if (statement.executeUpdate() == 0) {
          connection.rollback();
          return Optional.empty();
        }
      }
      insertRevision(connection, id, expectedVersion + 1, title, summary, category, fields, steps, success, now);
      connection.commit();
      return find(id);
    } catch (SQLException e) {
      throw new IllegalStateException("failed to update runbook", e);
    }
  }

  public synchronized List<Revision> revisions(String id) {
    return revisionQuery("SELECT * FROM runbook_revision WHERE runbook_id = ? ORDER BY version DESC", id);
  }

  public synchronized Optional<Runbook> rollback(String id, int targetVersion, int expectedVersion) {
    Optional<Revision> target = revisionQuery("SELECT * FROM runbook_revision WHERE runbook_id = ? AND version = ?", id, targetVersion).stream().findFirst();
    if (target.isEmpty()) return Optional.empty();
    Revision revision = target.get();
    return update(id, revision.title(), revision.summary(), revision.category(), revision.fields(), revision.steps(), revision.success(), expectedVersion);
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

  private void insertRevision(String id, int version, String title, String summary, String category,
      List<Field> fields, List<String> steps, List<String> success, String createdAt) {
    update("INSERT INTO runbook_revision(runbook_id,version,title,summary,category,fields_json,steps_json,success_json,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
        id, version, title, summary, category, encode(fields), encode(steps), encode(success), createdAt);
  }

  private void insertRevision(Connection connection, String id, int version, String title, String summary, String category,
      List<Field> fields, List<String> steps, List<String> success, String createdAt) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "INSERT INTO runbook_revision(runbook_id,version,title,summary,category,fields_json,steps_json,success_json,created_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
      bind(statement, new Object[] {id, version, title, summary, category, encode(fields), encode(steps), encode(success), createdAt});
      statement.executeUpdate();
    }
  }

  private List<Revision> revisionQuery(String sql, Object... args) {
    List<Revision> result = new ArrayList<>();
    try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, args);
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          result.add(new Revision(rs.getString("runbook_id"), rs.getInt("version"), rs.getString("title"),
              rs.getString("summary"), rs.getString("category"), decodeFields(rs.getString("fields_json")),
              decodeStrings(rs.getString("steps_json")), decodeStrings(rs.getString("success_json")), rs.getString("created_at")));
        }
      }
      return result;
    } catch (SQLException e) {
      throw new IllegalStateException("failed to query runbook revisions", e);
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
      statement.execute("CREATE TABLE IF NOT EXISTS runbook_revision(runbook_id TEXT NOT NULL,version INTEGER NOT NULL,title TEXT NOT NULL,summary TEXT NOT NULL,category TEXT NOT NULL,fields_json TEXT NOT NULL,steps_json TEXT NOT NULL,success_json TEXT NOT NULL,created_at TEXT NOT NULL,PRIMARY KEY(runbook_id,version))");
    }
  }

  private Connection open() throws SQLException { return DriverManager.getConnection(url); }
}
