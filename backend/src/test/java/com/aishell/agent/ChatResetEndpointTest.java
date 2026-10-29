package com.aishell.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.aishell.terminal.FakeTerminal;
import com.aishell.terminal.TerminalSessions;
import com.aishell.web.TicketService;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ChatResetEndpointTest.FakeModelConfig.class)
@Timeout(60)
class ChatResetEndpointTest {

  private static final String BASE = "http://127.0.0.1:";

  @LocalServerPort private int port;

  @Autowired private TicketService tickets;

  @Autowired private TerminalSessions terminals;

  @Autowired private SqliteAgentSessionService agentSessions;

  private final HttpClient client = HttpClient.newHttpClient();
  private FakeTerminal terminal;

  /** A fresh data directory per run keeps the epoch assertions deterministic. */
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    String dataDir = "target/test-data-reset-" + UUID.randomUUID();
    registry.add("ai-shell.token", () -> "test-token");
    registry.add("ai-shell.data.dir", () -> dataDir);
    registry.add("ai-shell.ai.command-timeout-seconds", () -> "5");
    registry.add("ai-shell.ai.turn-timeout-seconds", () -> "30");
  }

  @BeforeEach
  void setUp() {
    terminal = FakeTerminal.create();
    terminal.register(terminals);
  }

  @AfterEach
  void tearDown() {
    terminal.close();
  }

  @Test
  void resetRotatesTheConversationAndKeepsTheOldSession() throws Exception {
    assertThat(postChat("please run the echo").statusCode()).isEqualTo(200);
    String history = history().body();
    assertThat(history)
        .contains("\"conversation\":\"" + terminal.sessionId() + "\"")
        .contains("tool said");

    HttpResponse<String> reset = reset();
    assertThat(reset.statusCode()).isEqualTo(200);
    String newKey = terminal.sessionId() + "#2";
    assertThat(reset.body()).contains(newKey);

    String afterReset = history().body();
    assertThat(afterReset).contains("\"conversation\":\"" + newKey + "\"");
    assertThat(afterReset).as("the new conversation starts empty").doesNotContain("tool said");

    assertThat(agentSessions.getSession("ai-shell", "local", terminal.sessionId(), Optional.empty())
            .blockingGet())
        .as("the old ADK session is kept")
        .isNotNull();

    assertThat(postChat("please run the echo again").statusCode()).isEqualTo(200);
    assertThat(agentSessions.getSession("ai-shell", "local", newKey, Optional.empty()).blockingGet())
        .as("the next turn lands in the new session")
        .isNotNull();
    assertThat(history().body()).contains("\"conversation\":\"" + newKey + "\"");
  }

  @Test
  void resetOfAnUnknownSessionIsRejected() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(BASE + port + "/api/chat/reset"))
            .header("Content-Type", "application/json")
            .header("X-AI-Shell-Ticket", tickets.issue("chat").ticket())
            .POST(HttpRequest.BodyPublishers.ofString("{\"session\":\"not-a-session\"}"))
            .build();

    assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(409);
  }

  @Test
  void resetRequiresAChatTicket() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(BASE + port + "/api/chat/reset"))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"session\":\"" + terminal.sessionId() + "\"}"))
            .build();

    assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(401);
  }

  private HttpResponse<InputStream> postChat(String message) throws Exception {
    String body =
        "{\"session\":\"" + terminal.sessionId() + "\",\"message\":\"" + message + "\"}";
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(BASE + port + "/api/chat"))
            .header("Content-Type", "application/json")
            .header("X-AI-Shell-Ticket", tickets.issue("chat").ticket())
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    HttpResponse<InputStream> response =
        client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    response.body().readAllBytes();
    return response;
  }

  private HttpResponse<String> reset() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(BASE + port + "/api/chat/reset"))
            .header("Content-Type", "application/json")
            .header("X-AI-Shell-Ticket", tickets.issue("chat").ticket())
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"session\":\"" + terminal.sessionId() + "\"}"))
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> history() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(
                    BASE + port + "/api/chat/history?session=" + terminal.sessionId()))
            .header("X-AI-Shell-Ticket", tickets.issue("chat").ticket())
            .GET()
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  @TestConfiguration
  static class FakeModelConfig {

    @Bean
    @Primary
    ChatEndpointTest.FakeModel fakeModel() {
      return new ChatEndpointTest.FakeModel();
    }
  }
}
