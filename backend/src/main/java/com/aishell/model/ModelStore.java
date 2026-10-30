package com.aishell.model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Model connection settings in SQLite. The API key column holds ciphertext produced by {@link
 * com.aishell.security.SecretCipher}; it is never returned to the webview.
 */
@Component
public class ModelStore {

  public record Row(
      String id, String name, String baseUrl, String model, String apiKey, String updatedAt) {}

  private static final String COLUMNS = "id, name, base_url, model, api_key, updated_at";

  private final String url;

  public ModelStore(@Value("${ai-shell.data.dir:${user.home}/.aishell}") String dataDir) {
    try {
      Path dir = Path.of(dataDir);
      Files.createDirectories(dir);
      this.url =
          "jdbc:sqlite:" + dir.resolve("ai-shell.db").toAbsolutePath() + "?busy_timeout=3000";
      initSchema();
    } catch (Exception e) {
      throw new IllegalStateException("failed to open the AI Shell database", e);
    }
  }

  public List<Row> list() {
    return query(
        "SELECT " + COLUMNS + " FROM model_profile ORDER BY created_at, id", ModelStore::toRow);
  }

  public Optional<Row> first() {
    return query(
            "SELECT " + COLUMNS + " FROM model_profile ORDER BY created_at, id LIMIT 1",
            ModelStore::toRow)
        .stream()
        .findFirst();
  }

  public Optional<Row> find(String id) {
    if (id == null || id.isBlank()) {
      return Optional.empty();
    }
    return query("SELECT " + COLUMNS + " FROM model_profile WHERE id = ?", ModelStore::toRow, id)
        .stream()
        .findFirst();
  }

  public Row insert(String name, String baseUrl, String model, String apiKey) {
    String id = UUID.randomUUID().toString();
    String now = Instant.now().toString();
    update(
        "INSERT INTO model_profile"
            + " (id, name, base_url, model, api_key, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
        id,
        name,
        baseUrl,
        model,
        apiKey,
        now,
        now);
    return new Row(id, name, baseUrl, model, apiKey, now);
  }

  /** A null apiKey keeps the stored one. */
  public Optional<Row> update(
      String id, String name, String baseUrl, String model, String apiKey) {
    String now = Instant.now().toString();
    int changed =
        apiKey == null
            ? update(
                "UPDATE model_profile SET name = ?, base_url = ?, model = ?, updated_at = ?"
                    + " WHERE id = ?",
                name,
                baseUrl,
                model,
                now,
                id)
            : update(
                "UPDATE model_profile SET name = ?, base_url = ?, model = ?, api_key = ?,"
                    + " updated_at = ? WHERE id = ?",
                name,
                baseUrl,
                model,
                apiKey,
                now,
                id);
    return changed == 0
        ? Optional.empty()
        : find(id);
  }

  public boolean delete(String id) {
    return update("DELETE FROM model_profile WHERE id = ?", id) > 0;
  }

  private void initSchema() {
    try (Connection connection = open();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          """
          CREATE TABLE IF NOT EXISTS model_profile (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            base_url TEXT NOT NULL,
            model TEXT NOT NULL,
            api_key TEXT NOT NULL,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
          )
          """);
      // Early builds stored a per-profile temperature, which is no longer configurable; leaving
      // the NOT NULL column in place would reject inserts that do not carry it.
      if (hasColumn(connection, "model_profile", "temperature")) {
        statement.executeUpdate("ALTER TABLE model_profile DROP COLUMN temperature");
      }
    } catch (SQLException e) {
      throw new IllegalStateException("failed to create the model profile table", e);
    }
  }

  private static boolean hasColumn(Connection connection, String table, String column)
      throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
      while (rows.next()) {
        if (column.equals(rows.getString("name"))) {
          return true;
        }
      }
    }
    return false;
  }

  private Connection open() throws SQLException {
    return DriverManager.getConnection(url);
  }

  private List<Row> query(String sql, RowMapper mapper, Object... params) {
    try (Connection connection = open();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, params);
      try (ResultSet rows = statement.executeQuery()) {
        java.util.ArrayList<Row> result = new java.util.ArrayList<>();
        while (rows.next()) {
          result.add(mapper.map(rows));
        }
        return result;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("model profile query failed", e);
    }
  }

  private int update(String sql, Object... params) {
    try (Connection connection = open();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, params);
      return statement.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("model profile write failed", e);
    }
  }

  private static void bind(PreparedStatement statement, Object... params) throws SQLException {
    for (int i = 0; i < params.length; i++) {
      statement.setObject(i + 1, params[i]);
    }
  }

  private static Row toRow(ResultSet rows) throws SQLException {
    return new Row(
        rows.getString("id"),
        rows.getString("name"),
        rows.getString("base_url"),
        rows.getString("model"),
        rows.getString("api_key"),
        rows.getString("updated_at"));
  }

  @FunctionalInterface
  private interface RowMapper {
    Row map(ResultSet rows) throws SQLException;
  }
}
