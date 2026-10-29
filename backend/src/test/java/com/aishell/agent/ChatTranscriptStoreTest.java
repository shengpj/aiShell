package com.aishell.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class ChatTranscriptStoreTest {

  @TempDir Path dir;

  private ChatTranscriptStore store() {
    return new ChatTranscriptStore(dir.toString(), new ObjectMapper());
  }

  @Test
  void historyReturnsTheNewestEntriesInAscendingOrder() {
    ChatTranscriptStore store = store();
    for (int i = 1; i <= 5; i++) {
      store.recordUser("h:22", "t" + i, "m" + i);
    }

    assertThat(store.count("h:22")).isEqualTo(5);
    assertThat(store.history("h:22")).hasSize(5);
    assertThat(store.history("h:22", 2)).extracting(entry -> entry.get("text"))
        .containsExactly("m4", "m5");
  }

  @Test
  void aiChunksMergeUntilACommandBreaksTheBubble() {
    ChatTranscriptStore store = store();
    store.recordUser("h:22", "t1", "check disk");
    store.recordAiText("h:22", "t1", "Let me ");
    store.recordAiText("h:22", "t1", "look.");
    store.recordCommand("h:22", "t1", command("started", "df -h"));
    store.recordCommand("h:22", "t1", command("finished", "df -h"));
    store.recordAiText("h:22", "t1", "The disk is fine.");

    List<Map<String, Object>> history = store().history("h:22");

    assertThat(history).extracting(entry -> entry.get("kind"))
        .containsExactly("user", "ai", "command", "ai");
    assertThat(history.get(0).get("text")).isEqualTo("check disk");
    assertThat(history.get(1).get("text")).isEqualTo("Let me look.");
    assertThat(history.get(2).get("command")).isEqualTo("df -h");
    assertThat(history.get(2).get("state")).isEqualTo("finished");
    assertThat(history.get(3).get("text")).isEqualTo("The disk is fine.");
    assertThat(history.get(2).get("id")).isNotEqualTo(history.get(3).get("id"));
  }

  @Test
  void finishedCommandUpdatesTheStartedCardInPlace() {
    ChatTranscriptStore store = store();
    store.recordCommand("h:22", "t1", command("started", "uptime"));
    Map<String, Object> finished = command("finished", "uptime");
    finished.put("exitCode", 0);
    finished.put("durationMs", 12);
    store.recordCommand("h:22", "t1", finished);

    List<Map<String, Object>> history = store.history("h:22");

    assertThat(history).hasSize(1);
    assertThat(history.get(0).get("state")).isEqualTo("finished");
    assertThat(history.get(0).get("exitCode")).isEqualTo(0);
    assertThat(history.get(0).get("durationMs")).isEqualTo(12);
  }

  @Test
  void refusedCommandGetsItsOwnCard() {
    ChatTranscriptStore store = store();
    Map<String, Object> refused = command("refused", "rm -rf /");
    refused.put("reason", "blocked by the destructive-command deny-list");
    store.recordCommand("h:22", "t1", refused);

    List<Map<String, Object>> history = store.history("h:22");

    assertThat(history).hasSize(1);
    assertThat(history.get(0).get("kind")).isEqualTo("command");
    assertThat(history.get(0).get("state")).isEqualTo("refused");
    assertThat(history.get(0).get("reason"))
        .isEqualTo("blocked by the destructive-command deny-list");
  }

  @Test
  void conversationsAreIsolated() {
    ChatTranscriptStore store = store();
    store.recordUser("a:22", "t1", "first host");
    store.recordUser("b:22", "t1", "second host");

    assertThat(store.history("a:22")).extracting(entry -> entry.get("text"))
        .containsExactly("first host");
    assertThat(store.history("b:22")).extracting(entry -> entry.get("text"))
        .containsExactly("second host");
    assertThat(store.history("c:22")).isEmpty();
  }

  private static Map<String, Object> command(String state, String command) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("state", state);
    data.put("command", command);
    return data;
  }
}
