package com.aishell.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Per-host conversation epochs (ADR 0011). Epoch 1 keeps the historical {@code host:port} key so
 * existing conversations stay readable; every "new conversation" rotates to {@code host:port#N}.
 * Old transcripts and ADK sessions are kept, they are simply no longer the active conversation.
 */
@Component
public class ConversationStore {

  private final String url;

  public ConversationStore(@Value("${ai-shell.data.dir:${user.home}/.aishell}") String dataDir) {
    try {
      Path dir = Path.of(dataDir);
      Files.createDirectories(dir);
      this.url =
          "jdbc:sqlite:" + dir.resolve("ai-shell.db").toAbsolutePath() + "?busy_timeout=3000";
      initSchema();
    } catch (Exception e) {
      throw new IllegalStateException("failed to open the conversation store", e);
    }
  }

  /** The conversation currently in use for this host. */
  public synchronized String current(String hostKey) {
    return key(hostKey, epoch(hostKey));
  }

  /** Rotates to a fresh ADK session id; returns the new conversation key. */
  public synchronized String next(String hostKey) {
    int next = epoch(hostKey) + 1;
    try (Connection connection = open();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO conversation_epoch(host_key, epoch, updated_at) VALUES(?, ?, ?)"
                    + " ON CONFLICT(host_key) DO UPDATE SET epoch = excluded.epoch,"
                    + " updated_at = excluded.updated_at")) {
      statement.setString(1, hostKey);
      statement.setInt(2, next);
      statement.setString(3, Instant.now().toString());
      statement.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("failed to rotate the conversation", e);
    }
    return key(hostKey, next);
  }

  static String key(String hostKey, int epoch) {
    return epoch <= 1 ? hostKey : hostKey + "#" + epoch;
  }

  private int epoch(String hostKey) {
    try (Connection connection = open();
        PreparedStatement statement =
            connection.prepareStatement("SELECT epoch FROM conversation_epoch WHERE host_key = ?")) {
      statement.setString(1, hostKey);
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next() ? rs.getInt(1) : 1;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("failed to read the conversation epoch", e);
    }
  }

  private void initSchema() throws SQLException {
    try (Connection connection = open();
        Statement statement = connection.createStatement()) {
      statement.execute("PRAGMA journal_mode=WAL");
      statement.execute(
          "CREATE TABLE IF NOT EXISTS conversation_epoch("
              + " host_key TEXT PRIMARY KEY,"
              + " epoch INTEGER NOT NULL,"
              + " updated_at TEXT NOT NULL)");
    }
  }

  private Connection open() throws SQLException {
    return DriverManager.getConnection(url);
  }
}
