package com.aishell.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

class TerminalBridgeHookTest {

  private static final Pattern TOKEN = Pattern.compile("<<AI_SHELL:([0-9a-f]+):BEGIN>>");
  private static final Duration WAIT = Duration.ofSeconds(3);

  private FakePty pty;
  private TerminalBridge bridge;
  private ExecutorService executor;
  private final List<byte[]> binary = Collections.synchronizedList(new ArrayList<>());
  private final List<String> text = Collections.synchronizedList(new ArrayList<>());
  private final List<String> audited = Collections.synchronizedList(new ArrayList<>());

  @BeforeEach
  void setUp() throws Exception {
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

    pty = new FakePty();
    bridge =
        new TerminalBridge(
            ws,
            Path.of("target/test-data/known_hosts"),
            new ObjectMapper(),
            (session, command, exitCode, cwd) ->
                audited.add(exitCode + "|" + cwd + "|" + command + "|" + session));
    bridge.attach(pty);
    executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @AfterEach
  void tearDown() {
    bridge.close();
    executor.shutdownNow();
  }

  @Test
  void installsTheHookAndBuffersKeystrokesUntilItIsLive() throws Exception {
    bridge.installHook("bash");

    String install = written(pty.pollWrite(WAIT));
    assertThat(install).contains("read -rs __p");

    bridge.write("ls\r".getBytes(StandardCharsets.UTF_8));
    assertThat(pty.pollWrite(Duration.ofMillis(200))).isNull();

    pty.emit(marker("I", ""));
    String payload = written(pty.pollWrite(WAIT));
    assertThat(payload).endsWith("\r");
    assertThat(decoded(payload.trim()))
        .contains("PROMPT_COMMAND")
        .contains("builtin history 1");

    pty.emit(marker("H", "bash"));
    assertThat(awaitText(frame -> frame.contains("\"state\":\"ready\""))).isTrue();
    assertThat(written(pty.pollWrite(WAIT))).isEqualTo("ls\r");
  }

  @Test
  void stripsHookMarkersFromTheTerminalStream() throws Exception {
    pty.emit("ab" + marker("H", "bash") + "cd");

    assertThat(awaitBinary(chunk -> chunk.contains("abcd"))).isTrue();
    assertThat(binaryText()).doesNotContain("AISHELL");
  }

  @Test
  void auditsUserCommandsWithExitCodeAndWorkingDirectory() throws Exception {
    pty.emit(marker("D", "3;" + b64("/tmp") + ";" + b64("echo hi")));

    assertThat(awaitAudit("3|/tmp|echo hi|" + bridge.id())).isTrue();
  }

  @Test
  void keepsAiWrapperLinesOutOfTheUserAudit() throws Exception {
    Future<AiCommandResult> future =
        executor.submit(() -> bridge.runAiCommand("echo AI-INSIDE", Duration.ofSeconds(10)));
    String token = tokenOf(written(pty.pollWrite(WAIT)));

    pty.emit("\r\n<<AI_SHELL:" + token + ":BEGIN>>\r\n");
    pty.emit(marker("D", "0;" + b64("/root") + ";" + b64("echo AI-INSIDE")));
    Thread.sleep(300);
    assertThat(audited).as("the inner command of the wrapper is not a user command").isEmpty();

    pty.emit("<<AI_SHELL:" + token + ":END:0>>\r\n");
    assertThat(future.get(5, TimeUnit.SECONDS).status())
        .isEqualTo(AiCommandResult.Status.COMPLETED);

    pty.emit(marker("D", "0;" + b64("/root") + ";" + b64(endLine(token))));
    Thread.sleep(300);
    assertThat(audited).as("the wrapper's exit-code line is not a user command").isEmpty();

    pty.emit(marker("D", "0;" + b64("/root") + ";" + b64("ls -la")));
    assertThat(awaitAudit("0|/root|ls -la|" + bridge.id())).isTrue();
    assertThat(audited).hasSize(1);
  }

  @Test
  void surfacesHistoryLimitsAndRecoversWhenTheyClear() throws Exception {
    pty.emit(marker("H", "bash"));
    assertThat(awaitText(frame -> frame.contains("\"state\":\"ready\""))).isTrue();
    long readyFrames = countText(frame -> frame.contains("\"state\":\"ready\""));

    pty.emit(marker("W", "ignorespace"));
    assertThat(
            awaitText(
                frame ->
                    frame.contains("\"state\":\"degraded\"") && frame.contains("ignorespace")))
        .isTrue();
    assertThat(binaryText()).doesNotContain("AISHELL");

    pty.emit(marker("W", ""));
    assertThat(awaitCount(frame -> frame.contains("\"state\":\"ready\""), readyFrames + 1))
        .as("the badge returns to ready after the limitation clears")
        .isTrue();
    assertThat(text).anySatisfy(frame -> assertThat(frame).contains("\"shell\":\"bash\""));
  }

  @Test
  void reportsUnavailableWhenTheShellIsNotSupported() throws Exception {
    bridge.installHook("fish");

    assertThat(awaitText(frame -> frame.contains("\"state\":\"unavailable\""))).isTrue();
    assertThat(pty.pollWrite(Duration.ofMillis(200))).isNull();
  }

  @Test
  void givesUpAndReleasesBufferedInputWhenTheShellNeverAnswers() throws Exception {
    bridge.installHook("bash");
    assertThat(written(pty.pollWrite(WAIT))).contains("read -rs __p");

    bridge.write("pwd\r".getBytes(StandardCharsets.UTF_8));
    assertThat(awaitText(frame -> frame.contains("\"state\":\"unavailable\""), 6_000)).isTrue();
    assertThat(writesText()).contains("pwd\r");
    assertThat(pty.received(3)).as("Ctrl-C aborts the pending installer").isTrue();
  }

  private long countText(Predicate<String> predicate) {
    synchronized (text) {
      return text.stream().filter(predicate).count();
    }
  }

  private boolean awaitCount(Predicate<String> predicate, long expected)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + 4_000;
    while (System.currentTimeMillis() < deadline) {
      if (countText(predicate) >= expected) {
        return true;
      }
      Thread.sleep(20);
    }
    return false;
  }

  private boolean awaitText(Predicate<String> predicate) throws InterruptedException {
    return awaitText(predicate, 4_000);
  }

  private boolean awaitText(Predicate<String> predicate, long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      synchronized (text) {
        for (String frame : text) {
          if (predicate.test(frame)) {
            return true;
          }
        }
      }
      Thread.sleep(20);
    }
    return false;
  }

  private boolean awaitAudit(String expected) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 3_000;
    while (System.currentTimeMillis() < deadline) {
      if (audited.contains(expected)) {
        return true;
      }
      Thread.sleep(20);
    }
    return false;
  }

  private boolean awaitBinary(Predicate<String> predicate) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 3_000;
    while (System.currentTimeMillis() < deadline) {
      if (predicate.test(binaryText())) {
        return true;
      }
      Thread.sleep(20);
    }
    return false;
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

  private String writesText() {
    StringBuilder all = new StringBuilder();
    for (byte[] chunk : pty.history()) {
      all.append(new String(chunk, StandardCharsets.UTF_8));
    }
    return all.toString();
  }

  private static String endLine(String token) {
    return "printf '\\n<<AI_SHELL:" + token + ":END:%s>>\\n' \"$?\"";
  }

  private static String written(byte[] chunk) {
    assertThat(chunk).as("nothing was written to the PTY").isNotNull();
    return new String(chunk, StandardCharsets.UTF_8);
  }

  private static String tokenOf(String script) {
    Matcher matcher = TOKEN.matcher(script);
    assertThat(matcher.find()).as("wrapper contains the begin marker: " + script).isTrue();
    return matcher.group(1);
  }

  private static String marker(String kind, String payload) {
    return "\u001b]633;AISHELL;" + kind + ";" + payload + "\u0007";
  }

  private static String b64(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String decoded(String payload) {
    return new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8);
  }
}
