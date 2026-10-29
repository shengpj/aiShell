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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The user-visible chat transcript, mirroring what the SSE stream shows. Commands are stored as the
 * same JSON payloads the live UI receives so a reload renders identical cards.
 */
@Component
public class ChatTranscriptStore {

  private final String url;
  private final ObjectMapper json;

  public ChatTranscriptStore(
      @Value("${ai-shell.data.dir:${user.home}/.aishell}") String dataDir, ObjectMapper json) {
    this.json = json;
    try {
      Path dir = Path.of(dataDir);
      Files.createDirectories(dir);
      this.url =
          "jdbc:sqlite:" + dir.resolve("ai-shell.db").toAbsolutePath() + "?busy_timeout=3000";
      initSchema();
    } catch (Exception e) {
      throw new IllegalStateException("failed to open the chat transcript database", e);
    }
  }

  public void recordUser(String conversation, String turn, String text) {
    insert(conversation, turn, "user", text, null, null);
  }

  public void recordError(String conversation, String turn, String text) {
    insert(conversation, turn, "error", text, null, null);
  }

  /** Streaming chunks extend the trailing assistant bubble until a command card or error breaks it. */
  public void recordAiText(String conversation, String turn, String text) {
    try (Connection connection = open()) {
      Long lastId = null;
      String lastRole = null;
      String lastTurn = null;
      try (PreparedStatement statement =
          connection.prepareStatement(
              "SELECT id, role, turn_id FROM chat_message"
                  + " WHERE conversation_id = ? ORDER BY id DESC LIMIT 1")) {
        statement.setString(1, conversation);
        try (ResultSet rs = statement.executeQuery()) {
          if (rs.next()) {
            lastId = rs.getLong("id");
            lastRole = rs.getString("role");
            lastTurn = rs.getString("turn_id");
          }
        }
      }
      if (lastId != null && "ai".equals(lastRole) && turn.equals(lastTurn)) {
        try (PreparedStatement statement =
            connection.prepareStatement(
                "UPDATE chat_message SET content = content || ? WHERE id = ?")) {
          statement.setString(1, text);
          statement.setLong(2, lastId);
          statement.executeUpdate();
        }
        return;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("chat transcript read failed", e);
    }
    insert(conversation, turn, "ai", text, null, null);
  }

  public void recordCommand(String conversation, String turn, Map<String, Object> data) {
    Object command = data.get("command");
    Object state = data.get("state");
    if (!(command instanceof String text) || text.isBlank() || !(state instanceof String phase)) {
      return;
    }
    String payload = json.writeValueAsString(data);
    if ("finished".equals(phase)) {
      int changed =
          update(
              "UPDATE chat_message SET content = ?, state = ? WHERE id = ("
                  + "SELECT id FROM chat_message WHERE conversation_id = ? AND turn_id = ?"
                  + " AND role = 'command' AND command = ? AND state = 'started'"
                  + " ORDER BY id DESC LIMIT 1)",
              payload,
              phase,
              conversation,
              turn,
              text);
      if (changed > 0) {
        return;
      }
    }
    insert(conversation, turn, "command", payload, text, phase);
  }

  public List<Map<String, Object>> history(String conversation) {
    return history(conversation, Integer.MAX_VALUE);
  }

  /** The newest {@code limit} entries, returned in ascending order so the UI can append them. */
  public List<Map<String, Object>> history(String conversation, int limit) {
    List<Map<String, Object>> entries = query(
        "SELECT id, turn_id, role, content, created_at FROM chat_message"
            + " WHERE conversation_id = ? ORDER BY id DESC LIMIT ?",
        rs -> {
          long id = rs.getLong("id");
          String role = rs.getString("role");
          String content = rs.getString("content");
          Map<String, Object> entry = new LinkedHashMap<>();
          entry.put("id", id);
          entry.put("turn", rs.getString("turn_id"));
          entry.put("at", rs.getString("created_at"));
          entry.put("kind", role);
          if ("command".equals(role)) {
            Object parsed = json.readValue(content, Map.class);
            if (parsed instanceof Map<?, ?> fields) {
              fields.forEach(
                  (key, value) -> {
                    if (key instanceof String name) {
                      entry.put(name, value);
                    }
                  });
            }
          } else {
            entry.put("text", content);
          }
          return entry;
        },
        conversation,
        limit);
    java.util.Collections.reverse(entries);
    return entries;
  }

  /**
   * The instant range a turn occupies in the ADK event log: from its user message until the next
   * user message. Null when the turn has no user row (nothing was persisted for it).
   */
  public TurnWindow turnWindow(String conversation, String turn) {
    List<Object[]> starts =
        query(
            "SELECT id, created_at FROM chat_message"
                + " WHERE conversation_id = ? AND turn_id = ? AND role = 'user'"
                + " ORDER BY id LIMIT 1",
            rs -> new Object[] {rs.getLong("id"), rs.getString("created_at")},
            conversation,
            turn);
    if (starts.isEmpty()) {
      return null;
    }
    long userId = (Long) starts.get(0)[0];
    Instant start = Instant.parse((String) starts.get(0)[1]);
    List<Instant> next =
        query(
            "SELECT created_at FROM chat_message"
                + " WHERE conversation_id = ? AND id > ? AND role = 'user'"
                + " ORDER BY id LIMIT 1",
            rs -> Instant.parse(rs.getString("created_at")),
            conversation,
            userId);
    return new TurnWindow(start, next.isEmpty() ? null : next.get(0));
  }

  public int deleteTurn(String conversation, String turn) {
    return update(
        "DELETE FROM chat_message WHERE conversation_id = ? AND turn_id = ?", conversation, turn);
  }

  public record TurnWindow(Instant start, Instant end) {}

  public int count(String conversation) {
    try (Connection connection = open();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM chat_message WHERE conversation_id = ?")) {
      statement.setString(1, conversation);
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next() ? rs.getInt(1) : 0;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("failed to count chat messages", e);
    }
  }

  private void insert(
      String conversation, String turn, String role, String content, String command, String state) {
    update(
        "INSERT INTO chat_message"
            + " (conversation_id, turn_id, role, content, command, state, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
        conversation,
        turn,
        role,
        content,
        command,
        state,
        Instant.now().toString());
  }

  private void initSchema() throws SQLException {
    try (Connection connection = open();
        Statement statement = connection.createStatement()) {
      statement.execute("PRAGMA journal_mode=WAL");
      statement.execute(
          """
          CREATE TABLE IF NOT EXISTS chat_message (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            conversation_id TEXT NOT NULL,
            turn_id TEXT NOT NULL,
            role TEXT NOT NULL,
            content TEXT NOT NULL,
            command TEXT,
            state TEXT,
            created_at TEXT NOT NULL
          )
          """);
      statement.execute(
          "CREATE INDEX IF NOT EXISTS chat_message_conversation"
              + " ON chat_message(conversation_id, id)");
      statement.execute(
          "CREATE INDEX IF NOT EXISTS chat_message_turn"
              + " ON chat_message(conversation_id, turn_id)");
    }
  }

  private Connection open() throws SQLException {
    return DriverManager.getConnection(url);
  }

  private int update(String sql, Object... args) {
    try (Connection connection = open();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, args);
      return statement.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("chat transcript write failed", e);
    }
  }

  private <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
    try (Connection connection = open();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, args);
      try (ResultSet rs = statement.executeQuery()) {
        List<T> rows = new ArrayList<>();
        while (rs.next()) {
          rows.add(mapper.map(rs));
        }
        return rows;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("chat transcript read failed", e);
    }
  }

  private static void bind(PreparedStatement statement, Object... args) throws SQLException {
    for (int i = 0; i < args.length; i++) {
      statement.setObject(i + 1, args[i]);
    }
  }

  private interface RowMapper<T> {
    T map(ResultSet rs) throws SQLException;
  }
}
