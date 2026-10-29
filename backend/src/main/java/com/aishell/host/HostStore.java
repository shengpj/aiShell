package com.aishell.host;

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

/**
 * Host and connection-profile storage in SQLite. Every operation opens its own connection and all
 * rows are soft deleted so audit records that mention a host keep resolving.
 */
@Component
public class HostStore {

  public record HostRow(String id, String name, String address, int port) {}

  public record ProfileRow(
      String id,
      String hostId,
      String name,
      String username,
      String authType,
      String privateKeyPath,
      String secret) {}

  private static final String COLUMNS =
      "id, name, address, port";
  private static final String PROFILE_COLUMNS =
      "id, host_id, name, username, auth_type, private_key_path, secret";

  private final String url;

  public HostStore(@Value("${ai-shell.data.dir:${user.home}/.aishell}") String dataDir) {
    try {
      Path dir = Path.of(dataDir);
      Files.createDirectories(dir);
      this.url = "jdbc:sqlite:" + dir.resolve("ai-shell.db").toAbsolutePath()
          + "?busy_timeout=3000";
      initSchema();
    } catch (Exception e) {
      throw new IllegalStateException("failed to open the AI Shell database", e);
    }
  }

  public List<HostRow> listHosts() {
    return query(
        "SELECT h.id, h.name, h.address, h.port FROM host h WHERE h.deleted_at IS NULL"
            + " ORDER BY COALESCE((SELECT MAX(p.created_at) FROM connection_profile p"
            + " WHERE p.host_id = h.id AND p.deleted_at IS NULL), h.created_at) DESC,"
            + " h.name, h.id",
        rs ->
            new HostRow(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("address"),
                rs.getInt("port")));
  }

  public Optional<HostRow> findHost(String id) {
    if (id == null || id.isBlank()) {
      return Optional.empty();
    }
    return query(
            "SELECT " + COLUMNS + " FROM host WHERE id = ? AND deleted_at IS NULL",
            rs ->
                new HostRow(
                    rs.getString("id"),
                    rs.getString("name"),
                    rs.getString("address"),
                    rs.getInt("port")),
            id)
        .stream()
        .findFirst();
  }

  public HostRow insertHost(String name, String address, int port) {
    String id = UUID.randomUUID().toString();
    String now = Instant.now().toString();
    update(
        "INSERT INTO host (id, name, address, port, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?)",
        id,
        name,
        address,
        port,
        now,
        now);
    return new HostRow(id, name, address, port);
  }

  public Optional<HostRow> updateHost(String id, String name, String address, int port) {
    int changed =
        update(
            "UPDATE host SET name = ?, address = ?, port = ?, updated_at = ?"
                + " WHERE id = ? AND deleted_at IS NULL",
            name,
            address,
            port,
            Instant.now().toString(),
            id);
    return changed == 0 ? Optional.empty() : Optional.of(new HostRow(id, name, address, port));
  }

  public boolean deleteHost(String id) {
    String now = Instant.now().toString();
    try (Connection connection = open()) {
      connection.setAutoCommit(false);
      try {
        int changed =
            run(
                connection,
                "UPDATE host SET deleted_at = ?, updated_at = ?"
                    + " WHERE id = ? AND deleted_at IS NULL",
                now,
                now,
                id);
        if (changed == 0) {
          connection.rollback();
          return false;
        }
        run(
            connection,
            "UPDATE connection_profile SET deleted_at = ?, updated_at = ?"
                + " WHERE host_id = ? AND deleted_at IS NULL",
            now,
            now,
            id);
        connection.commit();
        return true;
      } catch (SQLException e) {
        connection.rollback();
        throw e;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("failed to delete host " + id, e);
    }
  }

  public List<ProfileRow> listProfiles(String hostId) {
    return query(
        "SELECT " + PROFILE_COLUMNS + " FROM connection_profile"
            + " WHERE host_id = ? AND deleted_at IS NULL ORDER BY created_at DESC, name, id",
        HostStore::toProfile,
        hostId);
  }

  public Optional<ProfileRow> findProfile(String id) {
    if (id == null || id.isBlank()) {
      return Optional.empty();
    }
    return query(
            "SELECT " + PROFILE_COLUMNS + " FROM connection_profile"
                + " WHERE id = ? AND deleted_at IS NULL",
            HostStore::toProfile,
            id)
        .stream()
        .findFirst();
  }

  public ProfileRow insertProfile(
      String hostId,
      String name,
      String username,
      String authType,
      String privateKeyPath,
      String secret) {
    String id = UUID.randomUUID().toString();
    String now = Instant.now().toString();
    update(
        "INSERT INTO connection_profile"
            + " (id, host_id, name, username, auth_type, private_key_path, secret,"
            + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        id,
        hostId,
        name,
        username,
        authType,
        privateKeyPath,
        secret,
        now,
        now);
    return new ProfileRow(id, hostId, name, username, authType, privateKeyPath, secret);
  }

  public Optional<ProfileRow> updateProfile(
      String id,
      String name,
      String username,
      String authType,
      String privateKeyPath,
      String secret) {
    int changed =
        update(
            "UPDATE connection_profile SET name = ?, username = ?, auth_type = ?,"
                + " private_key_path = ?, secret = ?, updated_at = ?"
                + " WHERE id = ? AND deleted_at IS NULL",
            name,
            username,
            authType,
            privateKeyPath,
            secret,
            Instant.now().toString(),
            id);
    return changed == 0 ? Optional.empty() : findProfile(id);
  }

  public boolean deleteProfile(String id) {
    String now = Instant.now().toString();
    try (Connection connection = open()) {
      connection.setAutoCommit(false);
      try {
        int changed =
            run(
                connection,
                "UPDATE connection_profile SET deleted_at = ?, updated_at = ?"
                    + " WHERE id = ? AND deleted_at IS NULL",
                now,
                now,
                id);
        if (changed == 0) {
          connection.rollback();
          return false;
        }
        softDeleteEmptyHost(connection, id, now);
        connection.commit();
        return true;
      } catch (SQLException e) {
        connection.rollback();
        throw e;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("failed to delete profile " + id, e);
    }
  }

  /**
   * A host only exists as a grouping for its connections; once the last one is gone the group
   * would linger as an empty row, so retire the host too. Rows stay soft deleted so audit
   * records still resolve.
   */
  private static void softDeleteEmptyHost(Connection connection, String profileId, String now)
      throws SQLException {
    String hostId;
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT host_id FROM connection_profile WHERE id = ?")) {
      statement.setObject(1, profileId);
      try (ResultSet rs = statement.executeQuery()) {
        if (!rs.next()) {
          return;
        }
        hostId = rs.getString("host_id");
      }
    }
    int remaining;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT COUNT(*) FROM connection_profile"
                + " WHERE host_id = ? AND deleted_at IS NULL")) {
      statement.setObject(1, hostId);
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        remaining = rs.getInt(1);
      }
    }
    if (remaining == 0) {
      run(
          connection,
          "UPDATE host SET deleted_at = ?, updated_at = ?"
              + " WHERE id = ? AND deleted_at IS NULL",
          now,
          now,
          hostId);
    }
  }

  private void initSchema() throws SQLException {
    try (Connection connection = open();
        Statement statement = connection.createStatement()) {
      statement.execute("PRAGMA journal_mode=WAL");
      statement.execute(
          """
          CREATE TABLE IF NOT EXISTS host (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            address TEXT NOT NULL,
            port INTEGER NOT NULL,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            deleted_at TEXT
          )
          """);
      statement.execute(
          """
          CREATE TABLE IF NOT EXISTS connection_profile (
            id TEXT PRIMARY KEY,
            host_id TEXT NOT NULL REFERENCES host(id),
            name TEXT NOT NULL,
            username TEXT NOT NULL,
            auth_type TEXT NOT NULL,
            private_key_path TEXT,
            secret TEXT,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            deleted_at TEXT
          )
          """);
      statement.execute(
          "CREATE INDEX IF NOT EXISTS host_active ON host(deleted_at)");
      statement.execute(
          "CREATE INDEX IF NOT EXISTS profile_host"
              + " ON connection_profile(host_id, deleted_at)");
    }
  }

  private Connection open() throws SQLException {
    return DriverManager.getConnection(url);
  }

  private int update(String sql, Object... args) {
    try (Connection connection = open()) {
      return run(connection, sql, args);
    } catch (SQLException e) {
      throw new IllegalStateException("database write failed", e);
    }
  }

  private static int run(Connection connection, String sql, Object... args) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, args);
      return statement.executeUpdate();
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
      throw new IllegalStateException("database read failed", e);
    }
  }

  private static void bind(PreparedStatement statement, Object... args) throws SQLException {
    for (int i = 0; i < args.length; i++) {
      statement.setObject(i + 1, args[i]);
    }
  }

  private static ProfileRow toProfile(ResultSet rs) throws SQLException {
    return new ProfileRow(
        rs.getString("id"),
        rs.getString("host_id"),
        rs.getString("name"),
        rs.getString("username"),
        rs.getString("auth_type"),
        rs.getString("private_key_path"),
        rs.getString("secret"));
  }

  private interface RowMapper<T> {
    T map(ResultSet rs) throws SQLException;
  }
}
