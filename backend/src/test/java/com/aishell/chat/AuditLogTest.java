package com.aishell.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.aishell.terminal.AiCommandResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AuditLogTest {

  @TempDir Path dir;

  @Test
  void recordsUserCommandsWithCwdAndExitCode() throws Exception {
    AuditLog log = new AuditLog(new ObjectMapper(), dir.toString());

    log.onUserCommand("s1", "cd /tmp", 0, "/root");
    log.onUserCommand("s1", "false", 1, "/tmp");

    List<String> lines = Files.readAllLines(dir.resolve("audit.jsonl"));
    assertThat(lines).hasSize(2);

    JsonNode first = new ObjectMapper().readTree(lines.get(0));
    assertThat(first.path("kind").asText()).isEqualTo("command");
    assertThat(first.path("initiator").asText()).isEqualTo("user");
    assertThat(first.path("session").asText()).isEqualTo("s1");
    assertThat(first.path("command").asText()).isEqualTo("cd /tmp");
    assertThat(first.path("exitCode").asInt()).isZero();
    assertThat(first.path("cwd").asText()).isEqualTo("/root");
    assertThat(first.path("at").asText()).isNotBlank();

    JsonNode second = new ObjectMapper().readTree(lines.get(1));
    assertThat(second.path("exitCode").asInt()).isEqualTo(1);
    assertThat(second.path("cwd").asText()).isEqualTo("/tmp");
  }

  @Test
  void rotatesBySizeAndKeepsTheConfiguredNumberOfFiles() throws Exception {
    AuditLog log = new AuditLog(new ObjectMapper(), dir.toString(), 300, 2);

    for (int i = 1; i <= 8; i++) {
      log.onUserCommand("s3", "echo " + i, 0, "/root");
    }

    assertThat(Files.exists(dir.resolve("audit.jsonl.1"))).isTrue();
    assertThat(Files.readString(dir.resolve("audit.jsonl"))).contains("echo 8");
    assertThat(Files.readString(dir.resolve("audit.jsonl.1"))).doesNotContain("echo 8");
    assertThat(Files.exists(dir.resolve("audit.jsonl.3")))
        .as("keeps at most maxFiles archives")
        .isFalse();
  }

  @Test
  void marksAiCommandsAsAiInitiated() throws Exception {
    AuditLog log = new AuditLog(new ObjectMapper(), dir.toString());

    log.recordCommand(
        "s2", "ls", new AiCommandResult(AiCommandResult.Status.COMPLETED, 0, "out", null, 15));

    JsonNode entry =
        new ObjectMapper().readTree(Files.readString(dir.resolve("audit.jsonl")).trim());
    assertThat(entry.path("initiator").asText()).isEqualTo("ai");
    assertThat(entry.path("status").asText()).isEqualTo("completed");
    assertThat(entry.path("output").asText()).isEqualTo("out");
    assertThat(entry.has("cwd")).isFalse();
  }

  @Test
  void readsRecentCommandsForOneSessionAndFiltersByQuery() throws Exception {
    AuditLog log = new AuditLog(new ObjectMapper(), dir.toString());

    log.onUserCommand("s1", "pwd", 0, "/root");
    log.onUserCommand("s2", "echo other", 0, "/tmp");
    log.recordCommand(
        "s1", "false", new AiCommandResult(AiCommandResult.Status.COMPLETED, 1, "failed", null, 4));

    AuditLog.History result = log.history("s1", "false", 100);

    assertThat(result.truncated()).isFalse();
    assertThat(result.entries()).hasSize(1);
    assertThat(result.entries().getFirst()).containsEntry("command", "false");
    assertThat(result.entries().getFirst()).containsEntry("initiator", "ai");
  }

  @Test
  void searchesAllEventKindsAcrossRotatedFilesNewestFirst() throws Exception {
    AuditLog log = new AuditLog(new ObjectMapper(), dir.toString(), 300, 2);
    log.onUserCommand("s1", "pwd", 0, "/root");
    log.recordFileDownload("s1", "/tmp/a.txt", 12);
    log.recordCommand("s1", "false", new AiCommandResult(AiCommandResult.Status.COMPLETED, 1, "bad", "failed", 4));

    AuditLog.AuditPage page = log.search(new AuditLog.AuditFilter(
        Instant.now().minusSeconds(60), Instant.now().plusSeconds(60), "s1", null, null, null, null, 0, 10));

    assertThat(page.total()).isEqualTo(3);
    assertThat(page.entries()).extracting(entry -> entry.get("kind"))
        .containsExactly("command", "file_download", "command");
    assertThat(page.entries().get(0)).containsEntry("source", "audit.jsonl");
  }

  @Test
  void searchFiltersStatusAndKeyword() throws Exception {
    AuditLog log = new AuditLog(new ObjectMapper(), dir.toString());
    log.recordCommand("s1", "echo ok", new AiCommandResult(AiCommandResult.Status.COMPLETED, 0, "healthy", null, 1));
    log.recordCommand("s1", "false", new AiCommandResult(AiCommandResult.Status.COMPLETED, 1, "failed", "failed", 1));

    AuditLog.AuditPage page = log.search(new AuditLog.AuditFilter(
        null, null, "s1", "ai", "command", "completed", "false", 0, 10));

    assertThat(page.total()).isEqualTo(1);
    assertThat(page.entries().getFirst()).containsEntry("command", "false");
  }
}
