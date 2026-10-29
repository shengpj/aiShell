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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
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
import reactor.core.publisher.Flux;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "ai-shell.token=test-token",
      "ai-shell.data.dir=target/test-data-chat",
      "ai-shell.ai.command-timeout-seconds=5",
      "ai-shell.ai.turn-timeout-seconds=30"
    })
@Import(ChatEndpointTest.FakeModelConfig.class)
@Timeout(60)
class ChatEndpointTest {

  private static final Path AUDIT = Path.of("target/test-data-chat/audit.jsonl");

  private final HttpClient client = HttpClient.newHttpClient();

  @LocalServerPort private int port;

  @Autowired private TicketService tickets;

  @Autowired private TerminalSessions terminals;

  @Autowired private SqliteAgentSessionService agentSessions;

  private FakeTerminal terminal;

  @BeforeEach
  void setUp() throws Exception {
    Files.deleteIfExists(AUDIT);
    terminal = FakeTerminal.create();
    terminal.register(terminals);
  }

  @AfterEach
  void tearDown() {
    terminal.close();
  }

  @Test
  void chatWithoutTicketIsRejected() throws Exception {
    HttpResponse<String> response =
        client.send(
            baseChatRequest(terminal.sessionId(), "hi").build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void ticketIssuedForAnotherPurposeIsRejected() throws Exception {
    String ticket = tickets.issue("terminal").ticket();
    HttpResponse<String> response =
        client.send(
            baseChatRequest(terminal.sessionId(), "hi")
                .header("X-AI-Shell-Ticket", ticket)
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void unknownTerminalSessionIsRejected() throws Exception {
    HttpResponse<String> response =
        client.send(
            baseChatRequest("not-a-session", "hi")
                .header("X-AI-Shell-Ticket", tickets.issue("chat").ticket())
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(409);
  }

  @Test
  void streamsCommandResultsAndFinalAnswer() throws Exception {
    HttpResponse<InputStream> response =
        client.send(
            baseChatRequest(terminal.sessionId(), "please run the echo")
                .header("X-AI-Shell-Ticket", tickets.issue("chat").ticket())
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type").orElse(""))
        .startsWith("text/event-stream");
    String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(body).contains("\"state\":\"started\"");
    assertThat(body).contains("\"state\":\"finished\"");
    assertThat(body).contains("\"exitCode\":0");
    assertThat(body).contains("event:text");
    assertThat(body).contains("event:done");
    assertThat(body).contains("CHAT-OK");

    assertThat(audit()).contains("\"command\":\"echo CHAT-OK\"").contains("\"kind\":\"command\"");
  }

  @Test
  void destructiveCommandIsRefusedAndAudited() throws Exception {
    HttpResponse<InputStream> response =
        client.send(
            baseChatRequest(terminal.sessionId(), "do something destructive")
                .header("X-AI-Shell-Ticket", tickets.issue("chat").ticket())
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());

    String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(body).contains("\"state\":\"refused\"");
    assertThat(body).contains("deny-list");
    assertThat(audit()).contains("\"kind\":\"refused\"").contains("rm -rf /");
  }

  @Test
  void historyRestoresTheStreamedEntries() throws Exception {
    client
        .send(
            baseChatRequest(terminal.sessionId(), "please run the echo")
                .header("X-AI-Shell-Ticket", tickets.issue("chat").ticket())
                .build(),
            HttpResponse.BodyHandlers.ofInputStream())
        .body()
        .readAllBytes();

    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:" + port + "/api/chat/history?session="
                            + terminal.sessionId()))
                .header("X-AI-Shell-Ticket", tickets.issue("chat").ticket())
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    String body = response.body();
    assertThat(body).contains("\"conversation\":\"" + terminal.sessionId() + "\"");
    assertThat(body).contains("\"kind\":\"user\"").contains("please run the echo");
    assertThat(body).contains("\"kind\":\"command\"").contains("echo CHAT-OK");
    assertThat(body).contains("\"kind\":\"ai\"").contains("tool said");
    assertThat(body.indexOf("please run the echo")).isLessThan(body.indexOf("echo CHAT-OK"));
    assertThat(body.indexOf("echo CHAT-OK")).isLessThan(body.indexOf("tool said"));
  }

  @Test
  void agentSessionPersistsTheTurnEventsForTheNextTurn() throws Exception {
    client
        .send(
            baseChatRequest(terminal.sessionId(), "please run the echo")
                .header("X-AI-Shell-Ticket", tickets.issue("chat").ticket())
                .build(),
            HttpResponse.BodyHandlers.ofInputStream())
        .body()
        .readAllBytes();

    com.google.adk.sessions.Session session =
        agentSessions
            .getSession("ai-shell", "local", terminal.sessionId(), java.util.Optional.empty())
            .blockingGet();

    assertThat(session).isNotNull();
    assertThat(session.events()).isNotEmpty();
    assertThat(session.events())
        .anyMatch(event -> "user".equals(event.author()))
        .anyMatch(event -> "ops_assistant".equals(event.author()));
  }

  @Test
  void historyCanBeLimitedToTheNewestEntries() throws Exception {
    client
        .send(
            baseChatRequest(terminal.sessionId(), "please run the echo")
                .header("X-AI-Shell-Ticket", tickets.issue("chat").ticket())
                .build(),
            HttpResponse.BodyHandlers.ofInputStream())
        .body()
        .readAllBytes();

    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:" + port + "/api/chat/history?session="
                            + terminal.sessionId()
                            + "&limit=1"))
                .header("X-AI-Shell-Ticket", tickets.issue("chat").ticket())
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"truncated\":true");
    assertThat(response.body()).contains("tool said");
    assertThat(response.body()).doesNotContain("please run the echo");
  }

  @Test
  void historyRequiresAChatTicket() throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:" + port + "/api/chat/history?session="
                            + terminal.sessionId()))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void historyOfAnUnknownSessionIsRejected() throws Exception {
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:" + port + "/api/chat/history?session=not-a-session"))
                .header("X-AI-Shell-Ticket", tickets.issue("chat").ticket())
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(409);
  }

  @Test
  void stopEndpointAcceptsChatTicket() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/agent/stop"))
            .header("X-AI-Shell-Ticket", tickets.issue("chat").ticket())
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"stopped\":0");
  }

  private HttpRequest.Builder baseChatRequest(String session, String message) {
    String body = "{\"session\":\"" + session + "\",\"message\":\"" + message + "\"}";
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/chat"))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(30))
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
  }

  private static String audit() throws Exception {
    return Files.exists(AUDIT) ? Files.readString(AUDIT, StandardCharsets.UTF_8) : "";
  }

  @TestConfiguration
  static class FakeModelConfig {

    @Bean
    @Primary
    FakeModel fakeModel() {
      return new FakeModel();
    }
  }

  /** Calls runCommand once, then reports the tool result as plain text. */
  static final class FakeModel implements ChatModel, StreamingChatModel {

    @Override
    public ChatResponse call(Prompt prompt) {
      return reply(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
      return Flux.just(reply(prompt));
    }

    private static ChatResponse reply(Prompt prompt) {
      List<Message> messages = prompt.getInstructions();
      Message last = messages.get(messages.size() - 1);
      if (last instanceof ToolResponseMessage toolResponse) {
        return text("tool said: " + toolResponse.getResponses().get(0).responseData());
      }
      String text = last instanceof UserMessage user ? user.getText() : "";
      String command = text.contains("destructive") ? "rm -rf /" : "echo CHAT-OK";
      return toolCall(command);
    }

    private static ChatResponse toolCall(String command) {
      AssistantMessage message =
          AssistantMessage.builder()
              .content("")
              .toolCalls(
                  List.of(
                      new AssistantMessage.ToolCall(
                          "call_1",
                          "function",
                          "runCommand",
                          "{\"command\":\"" + command + "\"}")))
              .build();
      return new ChatResponse(List.of(new Generation(message)));
    }

    private static ChatResponse text(String text) {
      return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
  }
}
