package com.aishell.terminal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * A terminal session backed by {@link FakePty} that answers AI command wrappers the way a POSIX
 * shell would, so chat endpoint tests can run without SSH.
 */
public final class FakeTerminal implements AutoCloseable {

  private static final Pattern BEGIN = Pattern.compile("<<AI_SHELL:([0-9a-f]+):BEGIN>>");

  private final FakePty pty;
  private final TerminalBridge bridge;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final Thread responder;

  private FakeTerminal(FakePty pty, TerminalBridge bridge) {
    this.pty = pty;
    this.bridge = bridge;
    this.responder = Thread.ofVirtual().name("fake-shell-" + bridge.id()).start(this::respond);
  }

  public static FakeTerminal create() {
    try {
      FakePty pty = new FakePty();
      WebSocketSession ws = mock(WebSocketSession.class);
      when(ws.isOpen()).thenReturn(true);
      TerminalBridge bridge =
          new TerminalBridge(ws, Path.of("target/test-data/known_hosts"), new ObjectMapper());
      bridge.attach(pty);
      return new FakeTerminal(pty, bridge);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public String sessionId() {
    return bridge.id();
  }

  public void register(TerminalSessions sessions) {
    sessions.register(bridge);
  }

  private void respond() {
    while (running.get()) {
      byte[] chunk;
      try {
        chunk = pty.pollWrite(java.time.Duration.ofMillis(100));
      } catch (InterruptedException e) {
        return;
      }
      if (chunk == null) {
        continue;
      }
      String script = new String(chunk, StandardCharsets.UTF_8);
      Matcher matcher = BEGIN.matcher(script);
      if (!matcher.find()) {
        continue;
      }
      String token = matcher.group(1);
      String output = script.contains("echo CHAT-OK") ? "CHAT-OK" : "ok";
      pty.emit(
          "\r\n<<AI_SHELL:"
              + token
              + ":BEGIN>>\r\n"
              + output
              + "\r\n<<AI_SHELL:"
              + token
              + ":END:0>>\r\n");
    }
  }

  @Override
  public void close() {
    running.set(false);
    responder.interrupt();
    bridge.close();
  }
}
