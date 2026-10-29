package com.aishell.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.adk.JsonBaseModel;
import com.google.adk.events.Event;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.GetSessionConfig;
import com.google.adk.sessions.ListEventsResponse;
import com.google.adk.sessions.ListSessionsResponse;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.SessionKey;
import com.google.common.collect.ImmutableList;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * ADK session storage in the same SQLite file as hosts and audit data. Events are persisted with
 * ADK's own JSON form, so a backend restart restores the model context instead of starting over.
 */
@Component
public class SqliteAgentSessionService implements BaseSessionService {

  private final String url;

  public SqliteAgentSessionService(
      @Value("${ai-shell.data.dir:${user.home}/.aishell}") String dataDir) {
    try {
      Path dir = Path.of(dataDir);
      Files.createDirectories(dir);
      this.url =
          "jdbc:sqlite:" + dir.resolve("ai-shell.db").toAbsolutePath() + "?busy_timeout=3000";
      initSchema();
    } catch (Exception e) {
      throw new IllegalStateException("failed to open the agent session database", e);
    }
  }

  @Override
  public Single<Session> createSession(
      String appName,
      String userId,
      ConcurrentMap<String, Object> state,
      String sessionId) {
    return Single.fromCallable(
        () -> {
          Map<String, Object> initial = state == null ? Map.of() : Map.copyOf(state);
          String now = Instant.now().toString();
          try (Connection connection = open();
              PreparedStatement statement =
                  connection.prepareStatement(
                      "INSERT INTO agent_session"
                          + " (app_name, user_id, session_id, state, created_at, updated_at)"
                          + " VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, appName);
            statement.setString(2, userId);
            statement.setString(3, sessionId);
            statement.setString(4, JsonBaseModel.toJsonString(initial));
            statement.setString(5, now);
            statement.setString(6, now);
            statement.executeUpdate();
          } catch (SQLException e) {
            throw new IllegalStateException(
                "session already exists: " + appName + "/" + userId + "/" + sessionId, e);
          }
          return Session.builder(sessionId)
              .appName(appName)
              .userId(userId)
              .state(new ConcurrentHashMap<>(initial))
              .events(new ArrayList<>())
              .lastUpdateTime(Instant.parse(now))
              .build();
        });
  }

  @Override
  public Maybe<Session> getSession(
      String appName, String userId, String sessionId, Optional<GetSessionConfig> config) {
    return Maybe.fromCallable(() -> loadSession(appName, userId, sessionId, config.orElse(null)));
  }

  @Override
  public Single<ListSessionsResponse> listSessions(String appName, String userId) {
    return Single.fromCallable(
        () ->
            ListSessionsResponse.builder()
                .sessions(
                    query(
                        "SELECT session_id, state, created_at FROM agent_session"
                            + " WHERE app_name = ? AND user_id = ? ORDER BY updated_at",
                        rs -> {
                          String id = rs.getString("session_id");
                          return Session.builder(id)
                              .appName(appName)
                              .userId(userId)
                              .state(new ConcurrentHashMap<>(parseState(rs.getString("state"))))
                              .events(new ArrayList<>())
                              .lastUpdateTime(Instant.parse(rs.getString("created_at")))
                              .build();
                        },
                        appName,
                        userId))
                .build());
  }

  @Override
  public Completable deleteSession(String appName, String userId, String sessionId) {
    return Completable.fromAction(
        () -> {
          update(
              "DELETE FROM agent_event WHERE app_name = ? AND user_id = ? AND session_id = ?",
              appName,
              userId,
              sessionId);
          update(
              "DELETE FROM agent_session WHERE app_name = ? AND user_id = ? AND session_id = ?",
              appName,
              userId,
              sessionId);
        });
  }

  @Override
  public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
    return Single.fromCallable(
        () ->
            ListEventsResponse.builder()
                .events(ImmutableList.copyOf(loadEvents(appName, userId, sessionId, null)))
                .build());
  }

  /**
   * Deletes the events appended inside a chat turn so the model stops seeing that turn as context.
   * Turns are sequential per conversation, so their event rows form contiguous time ranges.
   */
  public int deleteEventsBetween(
      String sessionId, Instant startInclusive, Instant endExclusive) {
    try (Connection connection = open()) {
      List<Long> seqs = new ArrayList<>();
      try (PreparedStatement select =
          connection.prepareStatement(
              "SELECT seq, created_at FROM agent_event WHERE session_id = ? ORDER BY seq")) {
        select.setString(1, sessionId);
        try (ResultSet rs = select.executeQuery()) {
          while (rs.next()) {
            Instant at = Instant.parse(rs.getString("created_at"));
            if (at.isBefore(startInclusive)) {
              continue;
            }
            if (endExclusive != null && !at.isBefore(endExclusive)) {
              continue;
            }
            seqs.add(rs.getLong("seq"));
          }
        }
      }
      if (seqs.isEmpty()) {
        return 0;
      }
      connection.setAutoCommit(false);
      try (PreparedStatement delete =
          connection.prepareStatement("DELETE FROM agent_event WHERE seq = ?")) {
        for (Long seq : seqs) {
          delete.setLong(1, seq);
          delete.addBatch();
        }
        int[] counts = delete.executeBatch();
        connection.commit();
        return counts.length;
      } catch (SQLException e) {
        connection.rollback();
        throw e;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("agent session database delete failed", e);
    }
  }

  @Override
  public Single<Event> appendEvent(Session session, Event event) {
    return Single.fromCallable(
        () -> {
          SessionKey key = session.sessionKey();
          update(
              "INSERT INTO agent_event"
                  + " (app_name, user_id, session_id, event_id, event_json, created_at)"
                  + " VALUES (?, ?, ?, ?, ?, ?)",
              key.appName(),
              key.userId(),
              key.id(),
              event.id(),
              event.toJson(),
              Instant.now().toString());
          Map<String, Object> delta = event.actions().stateDelta();
          if (delta != null) {
            delta.forEach(session.state()::put);
          }
          session.addEvent(event);
          Instant now = Instant.now();
          session.lastUpdateTime(now);
          update(
              "UPDATE agent_session SET state = ?, updated_at = ?"
                  + " WHERE app_name = ? AND user_id = ? AND session_id = ?",
              JsonBaseModel.toJsonString(session.state()),
              now.toString(),
              key.appName(),
              key.userId(),
              key.id());
          return event;
        });
  }

  private Session loadSession(
      String appName, String userId, String sessionId, GetSessionConfig config) {
    List<String[]> rows =
        query(
            "SELECT state, created_at FROM agent_session"
                + " WHERE app_name = ? AND user_id = ? AND session_id = ?",
            rs -> new String[] {rs.getString("state"), rs.getString("created_at")},
            appName,
            userId,
            sessionId);
    if (rows.isEmpty()) {
      return null;
    }
    List<Event> events = loadEvents(appName, userId, sessionId, config);
    return Session.builder(sessionId)
        .appName(appName)
        .userId(userId)
        .state(new ConcurrentHashMap<>(parseState(rows.get(0)[0])))
        .events(events)
        .lastUpdateTime(Instant.parse(rows.get(0)[1]))
        .build();
  }

  private List<Event> loadEvents(
      String appName, String userId, String sessionId, GetSessionConfig config) {
    List<Event> events =
        query(
            "SELECT event_json FROM agent_event"
                + " WHERE app_name = ? AND user_id = ? AND session_id = ? ORDER BY seq",
            rs -> Event.fromJsonString(rs.getString("event_json"), Event.class),
            appName,
            userId,
            sessionId);
    if (config == null) {
      return events;
    }
    long after = config.afterTimestamp().map(Instant::toEpochMilli).orElse(Long.MIN_VALUE);
    if (config.afterTimestamp().isPresent()) {
      events = events.stream().filter(event -> event.timestamp() >= after).toList();
    }
    Optional<Integer> recent = config.numRecentEvents().filter(count -> count > 0);
    if (recent.isPresent() && events.size() > recent.get()) {
      return new ArrayList<>(events.subList(events.size() - recent.get(), events.size()));
    }
    return events;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseState(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return JsonBaseModel.getMapper()
          .readValue(json, new TypeReference<ConcurrentHashMap<String, Object>>() {});
    } catch (Exception e) {
      return Map.of();
    }
  }

  private void initSchema() throws SQLException {
    try (Connection connection = open();
        Statement statement = connection.createStatement()) {
      statement.execute("PRAGMA journal_mode=WAL");
      statement.execute(
          """
          CREATE TABLE IF NOT EXISTS agent_session (
            app_name TEXT NOT NULL,
            user_id TEXT NOT NULL,
            session_id TEXT NOT NULL,
            state TEXT NOT NULL,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            PRIMARY KEY (app_name, user_id, session_id)
          )
          """);
      statement.execute(
          """
          CREATE TABLE IF NOT EXISTS agent_event (
            seq INTEGER PRIMARY KEY AUTOINCREMENT,
            app_name TEXT NOT NULL,
            user_id TEXT NOT NULL,
            session_id TEXT NOT NULL,
            event_id TEXT NOT NULL,
            event_json TEXT NOT NULL,
            created_at TEXT NOT NULL
          )
          """);
      statement.execute(
          "CREATE INDEX IF NOT EXISTS agent_event_session"
              + " ON agent_event(app_name, user_id, session_id, seq)");
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
      throw new IllegalStateException("agent session database write failed", e);
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
      throw new IllegalStateException("agent session database read failed", e);
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
