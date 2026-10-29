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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Flux;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ChatDeleteEndpointTest.EchoModelConfig.class)
@Timeout(60)
class ChatDeleteEndpointTest {

  private static final String BASE = "http://127.0.0.1:";

  @LocalServerPort private int port;

  @Autowired private TicketService tickets;

  @Autowired private TerminalSessions terminals;

  @Autowired private SqliteAgentSessionService agentSessions;

  private final HttpClient client = HttpClient.newHttpClient();
  private FakeTerminal terminal;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    String dataDir = "target/test-data-delete-" + UUID.randomUUID();
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
  void deletedTurnIsGoneFromHistoryAndFromTheNextPrompt() throws Exception {
    assertThat(postChat("t1", "alpha request")).contains("reply to alpha request after 1 user");
    assertThat(postChat("t2", "beta request")).contains("reply to beta request after 2 user");

    HttpResponse<String> delete = delete("t1");
    assertThat(delete.statusCode()).isEqualTo(200);
    assertThat(delete.body()).contains("\"turn\":\"t1\"").contains("\"messages\":2");

    String history = history();
    assertThat(history).doesNotContain("alpha request").contains("beta request");

    com.google.adk.sessions.Session session =
        agentSessions
            .getSession("ai-shell", "local", terminal.sessionId(), java.util.Optional.empty())
            .blockingGet();
    assertThat(session).isNotNull();
    List<String> texts =
        session.events().stream()
            .map(event -> event.content().map(content -> content.text()).orElse(""))
            .toList();
    assertThat(texts)
        .as("the deleted turn is gone from the ADK session but the next one stays")
        .anyMatch(text -> text.contains("beta request"))
        .allMatch(text -> !text.contains("alpha request"));

    assertThat(postChat("t3", "gamma request"))
        .as("the model prompt now holds only the two remaining user messages")
        .contains("reply to gamma request after 2 user")
        .doesNotContain("alpha request");
  }

  @Test
  void deleteRequiresAChatTicket() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(BASE + port + "/api/chat/delete"))
            .header("Content-Type", "application/json")
            .POST(body(terminal.sessionId(), "t1"))
            .build();

    assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(401);
  }

  @Test
  void deleteOfAnUnknownTurnIsRejected() throws Exception {
    assertThat(delete("t1").statusCode()).isEqualTo(404);
  }

  @Test
  void deleteOfAnUnknownTerminalIsRejected() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(BASE + port + "/api/chat/delete"))
            .header("Content-Type", "application/json")
            .header("X-AI-Shell-Ticket", tickets.issue("chat").ticket())
            .POST(body("not-a-session", "t1"))
            .build();

    assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
        .isEqualTo(409);
  }

  private String postChat(String turn, String message) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(BASE + port + "/api/chat"))
            .header("Content-Type", "application/json")
            .header("X-AI-Shell-Ticket", tickets.issue("chat").ticket())
            .timeout(Duration.ofSeconds(30))
            .POST(body(terminal.sessionId(), turn, message))
            .build();
    HttpResponse<InputStream> response =
        client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream stream = response.body()) {
      assertThat(response.statusCode()).isEqualTo(200);
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private HttpResponse<String> delete(String turn) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(BASE + port + "/api/chat/delete"))
            .header("Content-Type", "application/json")
            .header("X-AI-Shell-Ticket", tickets.issue("chat").ticket())
            .POST(body(terminal.sessionId(), turn))
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private String history() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(
                    BASE + port + "/api/chat/history?session=" + terminal.sessionId()))
            .header("X-AI-Shell-Ticket", tickets.issue("chat").ticket())
            .GET()
            .build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return response.body();
  }

  private static HttpRequest.BodyPublisher body(String session, String turn) {
    return HttpRequest.BodyPublishers.ofString(
        "{\"session\":\"" + session + "\",\"turn\":\"" + turn + "\"}",
        StandardCharsets.UTF_8);
  }

  private static HttpRequest.BodyPublisher body(String session, String turn, String message) {
    return HttpRequest.BodyPublishers.ofString(
        "{\"session\":\""
            + session
            + "\",\"turn\":\""
            + turn
            + "\",\"message\":\""
            + message
            + "\"}",
        StandardCharsets.UTF_8);
  }

  @TestConfiguration
  static class EchoModelConfig {

    @Bean
    @Primary
    EchoModel echoModel() {
      return new EchoModel();
    }
  }

  /** Replies with the latest user message so the prompt history is visible in the stream. */
  static final class EchoModel implements ChatModel, StreamingChatModel {

    @Override
    public ChatResponse call(Prompt prompt) {
      String last = "";
      int users = 0;
      for (Message message : prompt.getInstructions()) {
        if (message instanceof UserMessage user) {
          users += 1;
          last = user.getText();
        }
      }
      return new ChatResponse(
          List.of(
              new Generation(
                  new AssistantMessage(
                      "reply to " + last + " after " + users + " user messages"))));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
      return Flux.just(call(prompt));
    }
  }
}
