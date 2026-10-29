package com.aishell.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import com.aishell.host.HostService;
import com.aishell.host.HostService.HostRequest;
import com.aishell.host.HostService.HostView;
import com.aishell.host.HostService.ProfileRequest;
import com.aishell.host.HostService.ProfileView;
import com.aishell.web.TicketService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.shell.InteractiveProcessShellFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "ai-shell.token=test-token",
      "ai-shell.data.dir=target/test-data",
      "ai-shell.master-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
      "ai-shell.cors.allowed-origins=http://localhost:1420"
    })
class TerminalWebSocketTest {

  private static SshServer sshd;
  private static Path knownHosts;

  @LocalServerPort private int port;

  @Autowired private TicketService tickets;

  @Autowired private HostService hosts;

  @Autowired private TerminalSessions terminals;

  @BeforeAll
  static void startSshServer() throws IOException {
    knownHosts = Path.of("target/test-data/known_hosts");
    sshd = SshServer.setUpDefaultServer();
    sshd.setHost("127.0.0.1");
    sshd.setPort(0);
    sshd.setKeyPairProvider(
        new SimpleGeneratorHostKeyProvider(Files.createTempFile("aishell-hostkey", ".ser")));
    sshd.setPasswordAuthenticator(
        (username, password, session) -> "test".equals(username) && "secret".equals(password));
    sshd.setShellFactory(new InteractiveProcessShellFactory());
    sshd.start();
  }

  @AfterAll
  static void stopSshServer() throws IOException {
    if (sshd != null) {
      sshd.stop(true);
    }
  }

  @Test
  void handshakeWithoutTicketIsRejected() {
    assertThatThrownBy(() -> connect(null).get(10, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class);
  }

  @Test
  void ticketCannotBeReused() throws Exception {
    TicketService.IssuedTicket issued = tickets.issue("terminal");
    WebSocketSession session = connect(issued.ticket()).get(10, TimeUnit.SECONDS);
    session.close();

    assertThatThrownBy(() -> connect(issued.ticket()).get(10, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class);
  }

  @Test
  void openWithMissingParametersReportsError() throws Exception {
    BlockingQueue<Object> messages = new LinkedBlockingQueue<>();
    WebSocketSession session = connect(tickets.issue("terminal").ticket(), messages)
        .get(10, TimeUnit.SECONDS);

    session.sendMessage(new TextMessage("{\"type\":\"open\",\"host\":\"\",\"user\":\"\"}"));

    assertThat(await(messages, "\"type\":\"error\"", Duration.ofSeconds(5))).contains("host");
    session.close();
  }

  @Test
  void sshShellRoundTripThroughTicketWebSocket() throws Exception {
    Files.deleteIfExists(knownHosts);
    BlockingQueue<Object> messages = new LinkedBlockingQueue<>();
    WebSocketSession session = connect(tickets.issue("terminal").ticket(), messages)
        .get(10, TimeUnit.SECONDS);

    session.sendMessage(
        new TextMessage(
            "{\"type\":\"open\",\"host\":\"127.0.0.1\",\"port\":"
                + sshd.getPort()
                + ",\"user\":\"test\",\"cols\":100,\"rows\":30}"));

    await(messages, "Are you sure you want to continue connecting", Duration.ofSeconds(20));
    sendInput(session, "yes\r");

    await(messages, "assword:", Duration.ofSeconds(20));
    sendInput(session, "secret\r");

    String opened = await(messages, "\"type\":\"opened\"", Duration.ofSeconds(20));
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("\"session\":\"([^\"]+)\"").matcher(opened);
    assertThat(matcher.find()).isTrue();
    assertThat(terminals.conversationKey(matcher.group(1)))
        .contains("127.0.0.1:" + sshd.getPort());

    session.sendMessage(new TextMessage("{\"type\":\"resize\",\"cols\":120,\"rows\":40}"));
    // The embedded shell runs on plain pipes (no PTY line discipline), so it needs CRLF.
    sendInput(session, "echo MARKER-123\r\n");
    await(messages, "MARKER-123\r", Duration.ofSeconds(20));

    session.sendMessage(new TextMessage("{\"type\":\"close\"}"));
    session.close(CloseStatus.NORMAL);
  }

  @Test
  void savedProfilePasswordConnectsWithoutPrompting() throws Exception {
    Files.deleteIfExists(knownHosts);
    HostView host =
        hosts.createHost(new HostRequest("Loopback password", "127.0.0.1", sshd.getPort()));
    ProfileView profile =
        hosts.addProfile(
            host.id(), new ProfileRequest("test@loopback", "test", "password", null, "secret"));
    BlockingQueue<Object> messages = new LinkedBlockingQueue<>();
    WebSocketSession session =
        connect(tickets.issue("terminal").ticket(), messages).get(10, TimeUnit.SECONDS);

    session.sendMessage(
        new TextMessage(
            "{\"type\":\"open\",\"profile\":\""
                + profile.id()
                + "\",\"cols\":100,\"rows\":30}"));

    await(messages, "Are you sure you want to continue connecting", Duration.ofSeconds(20));
    sendInput(session, "yes\r");

    String beforeOpen = await(messages, "\"type\":\"opened\"", Duration.ofSeconds(20));
    assertThat(beforeOpen).doesNotContain("s password:");

    sendInput(session, "echo PROFILE-OK\r\n");
    await(messages, "PROFILE-OK\r", Duration.ofSeconds(20));

    session.sendMessage(new TextMessage("{\"type\":\"close\"}"));
    session.close(CloseStatus.NORMAL);
  }

  private java.util.concurrent.CompletableFuture<WebSocketSession> connect(String ticket) {
    return connect(ticket, new LinkedBlockingQueue<>());
  }

  private java.util.concurrent.CompletableFuture<WebSocketSession> connect(
      String ticket, BlockingQueue<Object> messages) {
    String query = ticket == null ? "" : "?ticket=" + ticket;
    return new StandardWebSocketClient()
        .execute(
            new AbstractWebSocketHandler() {
              @Override
              protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                messages.add(message.getPayload());
              }

              @Override
              protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
                byte[] payload = new byte[message.getPayload().remaining()];
                message.getPayload().get(payload);
                messages.add(payload);
              }
            },
            "ws://127.0.0.1:" + port + "/ws/terminal" + query);
  }

  private static void sendInput(WebSocketSession session, String text) throws IOException {
    session.sendMessage(new BinaryMessage(text.getBytes(StandardCharsets.UTF_8)));
  }

  private static String await(BlockingQueue<Object> messages, String needle, Duration timeout)
      throws InterruptedException {
    StringBuilder seen = new StringBuilder();
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      Object message = messages.poll(100, TimeUnit.MILLISECONDS);
      if (message == null) {
        continue;
      }
      if (message instanceof byte[] bytes) {
        seen.append(new String(bytes, StandardCharsets.UTF_8));
      } else {
        seen.append(message);
      }
      if (seen.toString().contains(needle)) {
        return seen.toString();
      }
    }
    fail("did not see '" + needle + "' within " + timeout + "; saw: " + seen);
    return seen.toString();
  }
}
