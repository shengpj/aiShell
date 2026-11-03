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
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Durable status records for Runbook-backed AI turns. */
@Component
public class RunbookExecutionStore {

  public record Execution(
      String id,
      String runbookId,
      int runbookVersion,
      String session,
      String turn,
      String status,
      String startedAt,
      String finishedAt,
      String message) {}

  private final String url;

  public RunbookExecutionStore(
      @Value("${ai-shell.data.dir:${user.home}/.aishell}") String dataDir) {
    try {
      Path dir = Path.of(dataDir);
      Files.createDirectories(dir);
      this.url = "jdbc:sqlite:" + dir.resolve("ai-shell.db").toAbsolutePath() + "?busy_timeout=3000";
      initSchema();
    } catch (Exception e) {
      throw new IllegalStateException("failed to open the runbook execution store", e);
    }
  }

  public String start(String runbookId, int runbookVersion, String session, String turn) {
    String id = UUID.randomUUID().toString();
    update("INSERT INTO runbook_execution(id,runbook_id,runbook_version,session_id,turn_id,status,started_at) VALUES(?,?,?,?,?,?,?)",
        id, runbookId, runbookVersion, session, turn, "running", Instant.now().toString());
    return id;
  }

  public void finish(String id, String status, String message) {
    if (id == null || id.isBlank()) return;
    update("UPDATE runbook_execution SET status=?,finished_at=?,message=? WHERE id=? AND finished_at IS NULL",
        status, Instant.now().toString(), message, id);
  }

  public List<Execution> list(String runbookId, int limit) {
    int capped = Math.max(1, Math.min(limit, 100));
    List<Execution> result = new ArrayList<>();
    try (Connection connection = open();
        PreparedStatement statement = connection.prepareStatement(
            "SELECT id,runbook_id,runbook_version,session_id,turn_id,status,started_at,finished_at,message"
                + " FROM runbook_execution WHERE runbook_id=? ORDER BY started_at DESC LIMIT ?")) {
      statement.setString(1, runbookId);
      statement.setInt(2, capped);
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          result.add(new Execution(rs.getString("id"), rs.getString("runbook_id"),
              rs.getInt("runbook_version"), rs.getString("session_id"), rs.getString("turn_id"),
              rs.getString("status"), rs.getString("started_at"), rs.getString("finished_at"),
              rs.getString("message")));
        }
      }
      return result;
    } catch (SQLException e) {
      throw new IllegalStateException("failed to query runbook executions", e);
    }
  }

  private void initSchema() throws SQLException {
    try (Connection connection = open(); Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE IF NOT EXISTS runbook_execution(id TEXT PRIMARY KEY,runbook_id TEXT NOT NULL,runbook_version INTEGER NOT NULL,session_id TEXT NOT NULL,turn_id TEXT NOT NULL,status TEXT NOT NULL,started_at TEXT NOT NULL,finished_at TEXT,message TEXT)");
      statement.execute("CREATE INDEX IF NOT EXISTS runbook_execution_runbook ON runbook_execution(runbook_id,started_at)");
    }
  }

  private int update(String sql, Object... args) {
    try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < args.length; index++) statement.setObject(index + 1, args[index]);
      return statement.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("failed to write runbook execution", e);
    }
  }

  private Connection open() throws SQLException { return DriverManager.getConnection(url); }
}
