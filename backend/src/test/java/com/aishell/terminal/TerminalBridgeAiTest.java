package com.aishell.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

class TerminalBridgeAiTest {

  private static final Pattern TOKEN = Pattern.compile("<<AI_SHELL:([0-9a-f]+):BEGIN>>");
  private static final Duration WAIT = Duration.ofSeconds(5);

  private FakePty pty;
  private TerminalBridge bridge;
  private WebSocketSession ws;
  private ExecutorService executor;

  @BeforeEach
  void setUp() throws Exception {
    ws = mock(WebSocketSession.class);
    when(ws.isOpen()).thenReturn(true);
    pty = new FakePty();
    bridge =
        new TerminalBridge(ws, Path.of("target/test-data/known_hosts"), new ObjectMapper());
    bridge.attach(pty);
    executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @AfterEach
  void tearDown() {
    bridge.close();
    executor.shutdownNow();
  }

  @Test
  void runsCommandCapturesOutputAndExitCode() throws Exception {
    Future<AiCommandResult> future =
        executor.submit(() -> bridge.runAiCommand("echo hi", Duration.ofSeconds(10)));
    String token = tokenOf(awaitScript());

    emitBegin(token);
    pty.emit("hi\r\n");
    emitEnd(token, 0);

    AiCommandResult result = future.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    assertThat(result.status()).isEqualTo(AiCommandResult.Status.COMPLETED);
    assertThat(result.exitCode()).isZero();
    assertThat(result.output()).contains("hi").doesNotContain("AI_SHELL");
  }

  @Test
  void escapeCancelsTheCommandAndSendsCtrlC() throws Exception {
    Future<AiCommandResult> future =
        executor.submit(() -> bridge.runAiCommand("sleep 100", Duration.ofSeconds(30)));
    String token = tokenOf(awaitScript());
    emitBegin(token);

    bridge.write(new byte[] {27});

    AiCommandResult result = future.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    assertThat(result.status()).isEqualTo(AiCommandResult.Status.CANCELLED);
    assertThat(pty.received(3)).as("Ctrl-C was sent to the PTY").isTrue();
  }

  @Test
  void passwordPromptHandsInputToTheUserThenResumes() throws Exception {
    Future<AiCommandResult> future =
        executor.submit(() -> bridge.runAiCommand("sudo ls /root", Duration.ofSeconds(10)));
    String token = tokenOf(awaitScript());
    emitBegin(token);

    pty.emit("[sudo] password for bob: ");
    verify(ws, timeout(3_000))
        .sendMessage(
            argThat(
                message ->
                    message instanceof TextMessage text
                        && text.getPayload().contains("\"state\":\"takeover\"")));

    bridge.write("s3cret\r".getBytes(StandardCharsets.UTF_8));
    assertThat(writesAsText()).contains("s3cret\r");

    pty.emit("total 0\r\n");
    emitEnd(token, 0);

    AiCommandResult result = future.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    assertThat(result.status()).isEqualTo(AiCommandResult.Status.COMPLETED);
  }

  @Test
  void manualTakeoverForwardsInput() throws Exception {
    Future<AiCommandResult> future =
        executor.submit(() -> bridge.runAiCommand("cat", Duration.ofSeconds(10)));
    String token = tokenOf(awaitScript());
    emitBegin(token);

    bridge.requestTakeover(true);
    bridge.write("hello\r".getBytes(StandardCharsets.UTF_8));
    assertThat(writesAsText()).contains("hello\r");

    emitEnd(token, 0);
    assertThat(future.get(WAIT.toSeconds(), TimeUnit.SECONDS).status())
        .isEqualTo(AiCommandResult.Status.COMPLETED);
  }

  @Test
  void dropsKeystrokesWhileTheAiCommandRuns() throws Exception {
    Future<AiCommandResult> future =
        executor.submit(() -> bridge.runAiCommand("sleep 100", Duration.ofSeconds(10)));
    String token = tokenOf(awaitScript());
    emitBegin(token);

    bridge.write("ls -la\r".getBytes(StandardCharsets.UTF_8));
    emitEnd(token, 0);

    assertThat(future.get(WAIT.toSeconds(), TimeUnit.SECONDS).status())
        .isEqualTo(AiCommandResult.Status.COMPLETED);
    assertThat(writesAsText()).doesNotContain("ls -la");
  }

  @Test
  void refusesWhenTheUserHasAnUnfinishedCommandLine() throws Exception {
    bridge.write("ls -".getBytes(StandardCharsets.UTF_8));

    AiCommandResult result = bridge.runAiCommand("echo hi", Duration.ofSeconds(2));

    assertThat(result.status()).isEqualTo(AiCommandResult.Status.UNAVAILABLE);
    assertThat(result.message()).contains("unfinished");
    assertThat(writesAsText()).doesNotContain("AI_SHELL");
  }

  @Test
  void refusesWhenAFullScreenProgramIsRunning() throws Exception {
    pty.emit("\u001B[?1049h");
    Thread.sleep(150);

    AiCommandResult result = bridge.runAiCommand("echo hi", Duration.ofSeconds(2));

    assertThat(result.status()).isEqualTo(AiCommandResult.Status.UNAVAILABLE);
    assertThat(result.message()).contains("full-screen");
  }

  @Test
  void timesOutAndInterrupts() throws Exception {
    Future<AiCommandResult> future =
        executor.submit(() -> bridge.runAiCommand("sleep 100", Duration.ofMillis(600)));
    String token = tokenOf(awaitScript());
    emitBegin(token);

    AiCommandResult result = future.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    assertThat(result.status()).isEqualTo(AiCommandResult.Status.TIMEOUT);
    assertThat(pty.received(3)).as("Ctrl-C was sent to the PTY").isTrue();
  }

  private String awaitScript() throws InterruptedException {
    byte[] chunk = pty.pollWrite(WAIT);
    assertThat(chunk).as("the AI wrapper was written to the PTY").isNotNull();
    return new String(chunk, StandardCharsets.UTF_8);
  }

  private static String tokenOf(String script) {
    Matcher matcher = TOKEN.matcher(script);
    assertThat(matcher.find()).as("wrapper contains the begin marker: " + script).isTrue();
    return matcher.group(1);
  }

  private void emitBegin(String token) {
    pty.emit("\r\n<<AI_SHELL:" + token + ":BEGIN>>\r\n");
  }

  private void emitEnd(String token, int code) {
    pty.emit("<<AI_SHELL:" + token + ":END:" + code + ">>\r\n");
  }

  private String writesAsText() {
    StringBuilder text = new StringBuilder();
    for (byte[] chunk : pty.history()) {
      text.append(new String(chunk, StandardCharsets.UTF_8));
    }
    return text.toString();
  }
}
