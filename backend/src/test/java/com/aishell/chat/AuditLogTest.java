package com.aishell.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.aishell.terminal.AiCommandResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
}
