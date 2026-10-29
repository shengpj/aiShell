package com.aishell.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aishell.host.HostService.ConnectTarget;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Live acceptance for the shell hook against a real host (ADR 0010). Skipped unless the SSH target
 * is provided: {@code AISHELL_LIVE_HOST}, {@code AISHELL_LIVE_USER}, {@code AISHELL_LIVE_KEY}, and
 * optional {@code AISHELL_LIVE_PORT} (22) / {@code AISHELL_LIVE_SHELL} (asserted when present).
 * The remote account's login shell decides which branch is exercised, so this is how the zsh branch
 * gets covered: point it at an account whose shell is zsh.
 */
@EnabledIfEnvironmentVariable(named = "AISHELL_LIVE_HOST", matches = "\\S+")
class ShellHookLiveTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(20);

  private final List<String> text = Collections.synchronizedList(new ArrayList<>());
  private final List<byte[]> binary = Collections.synchronizedList(new ArrayList<>());
  private final List<String> audited = Collections.synchronizedList(new ArrayList<>());
  private TerminalBridge bridge;

  @AfterEach
  void tearDown() {
    if (bridge != null) {
      bridge.close();
    }
  }

  @Test
  void auditsUserCommandsThroughTheRealShellHook() throws Exception {
    String host = System.getenv("AISHELL_LIVE_HOST");
    String user = System.getenv("AISHELL_LIVE_USER");
    String key = System.getenv("AISHELL_LIVE_KEY");
    int port = Integer.parseInt(System.getenv().getOrDefault("AISHELL_LIVE_PORT", "22"));
    String expectedShell = System.getenv("AISHELL_LIVE_SHELL");

    WebSocketSession ws = mock(WebSocketSession.class);
    when(ws.isOpen()).thenReturn(true);
    doAnswer(
            invocation -> {
              WebSocketMessage<?> message = invocation.getArgument(0);
              if (message instanceof BinaryMessage binaryMessage) {
                var payload = binaryMessage.getPayload();
                byte[] bytes = new byte[payload.remaining()];
                payload.get(bytes);
                binary.add(bytes);
              } else if (message instanceof TextMessage textMessage) {
                text.add(textMessage.getPayload());
              }
              return null;
            })
        .when(ws)
        .sendMessage(any(WebSocketMessage.class));

    bridge =
        new TerminalBridge(
            ws,
            Path.of("target/test-data/live-known_hosts"),
            new ObjectMapper(),
            (session, command, exitCode, cwd) ->
                audited.add(exitCode + "|" + cwd + "|" + command));
    bridge.open(new ConnectTarget(host, port, user, key, null), 120, 30);
    Thread.ofVirtual().name("host-key-answer").start(this::answerHostKeyPrompt);

    assertThat(await(() -> text.stream().anyMatch(frame -> frame.contains("\"opened\""))))
        .as("terminal opened; text=" + text + " binary=" + binaryText())
        .isTrue();
    assertThat(await(() -> text.stream().anyMatch(frame -> frame.contains("\"state\":\"ready\""))))
        .as("hook ready; seen frames: " + text)
        .isTrue();
    String ready = text.stream().filter(frame -> frame.contains("\"state\":\"ready\"")).findFirst()
        .orElseThrow();
    if (expectedShell != null && !expectedShell.isBlank()) {
      assertThat(ready).contains("\"shell\":\"" + expectedShell.trim() + "\"");
    }
    assertThat(binaryText()).doesNotContain("\u001b]633;AISHELL;");

    bridge.write("echo LIVE-HOOK-OK\r".getBytes(StandardCharsets.UTF_8));
    assertThat(await(() -> audited.stream().anyMatch(entry -> entry.endsWith("|echo LIVE-HOOK-OK"))))
        .as("user command audited: " + audited)
        .isTrue();
    assertThat(audited.stream().filter(entry -> entry.endsWith("|echo LIVE-HOOK-OK")).findFirst())
        .hasValueSatisfying(entry -> assertThat(entry).startsWith("0|/root|"));

    bridge.write("false\r".getBytes(StandardCharsets.UTF_8));
    assertThat(await(() -> audited.stream().anyMatch(entry -> entry.equals("1|/root|false"))))
        .as("exit code captured: " + audited)
        .isTrue();

    bridge.write("cd /tmp\r".getBytes(StandardCharsets.UTF_8));
    assertThat(await(() -> audited.stream().anyMatch(entry -> entry.endsWith("|cd /tmp")))).isTrue();
    bridge.write("pwd\r".getBytes(StandardCharsets.UTF_8));
    assertThat(await(() -> audited.stream().anyMatch(entry -> entry.equals("0|/tmp|pwd"))))
        .as("working directory captured: " + audited)
        .isTrue();

    if ("bash".equals(expectedShell)) {
      long readyFrames = countText(frame -> frame.contains("\"state\":\"ready\""));
      bridge.write("HISTCONTROL=ignorespace\r".getBytes(StandardCharsets.UTF_8));
      assertThat(
              await(
                  () ->
                      text.stream()
                          .anyMatch(
                              frame ->
                                  frame.contains("\"state\":\"degraded\"")
                                      && frame.contains("ignorespace"))))
          .as("history limitation surfaced: " + text)
          .isTrue();
      bridge.write("HISTCONTROL=\r".getBytes(StandardCharsets.UTF_8));
      assertThat(await(() -> countText(frame -> frame.contains("\"state\":\"ready\"")) > readyFrames))
          .as("badge recovers after the limitation clears: " + text)
          .isTrue();
    }
  }

  private long countText(java.util.function.Predicate<String> predicate) {
    synchronized (text) {
      return text.stream().filter(predicate).count();
    }
  }

  private void answerHostKeyPrompt() {
    long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
    try {
      while (System.currentTimeMillis() < deadline) {
        String shown = binaryText();
        if (shown.contains("(yes/no)")
            || shown.contains("continue connecting")
            || shown.contains("fingerprint")) {
          bridge.write("yes\r".getBytes(StandardCharsets.UTF_8));
          return;
        }
        if (text.stream().anyMatch(frame -> frame.contains("\"state\":\"ready\""))) {
          return;
        }
        Thread.sleep(50);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private String binaryText() {
    StringBuilder all = new StringBuilder();
    synchronized (binary) {
      for (byte[] chunk : binary) {
        all.append(new String(chunk, StandardCharsets.UTF_8));
      }
    }
    return all.toString();
  }

  private boolean await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
    while (System.currentTimeMillis() < deadline) {
      if (condition.getAsBoolean()) {
        return true;
      }
      Thread.sleep(50);
    }
    return false;
  }
}
