package com.aishell.spike;

import static org.assertj.core.api.Assertions.assertThat;

import com.aishell.spike.agent.SpikeAgentConfig;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.models.springai.SpringAI;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

class SpikeAdapterTest {

  private static final String APP = "spike";
  private static final String USER = "user";

  private FakeChatModel model;
  private InMemorySessionService sessions;
  private Runner runner;

  @BeforeEach
  void setUp() {
    model = new FakeChatModel();
    LlmAgent agent = new SpikeAgentConfig().spikeAgent(new SpringAI(model, "fake-model"));
    sessions = new InMemorySessionService();
    runner =
        Runner.builder().agent(agent).appName(APP).sessionService(sessions).build();
  }

  @Test
  void functionToolRoundTripAndHistory() {
    createSession("s1");

    List<Event> turn1 =
        run("s1", "What is the server time in UTC?", RunConfig.builder().build());

    assertThat(eventsText(turn1)).contains("UTC").containsPattern("T\\d{2}:\\d{2}");
    assertThat(sawFunctionCall(turn1)).as("model asked for getServerTime").isTrue();

    assertThat(model.prompts).hasSize(2);
    assertThat(toolNames(model.prompts.get(0))).containsExactly("getServerTime");
    assertThat(model.prompts.get(1).getInstructions())
        .anyMatch(m -> m instanceof ToolResponseMessage);

    List<Event> turn2 =
        run("s1", "Which timezone did you just check?", RunConfig.builder().build());

    assertThat(eventsText(turn2)).contains("UTC");

    List<Message> turn2Prompt = model.prompts.get(2).getInstructions();
    assertThat(turn2Prompt)
        .anyMatch(
            m -> m instanceof UserMessage um && um.getText().contains("server time in UTC"));
    assertThat(turn2Prompt)
        .anyMatch(
            m -> m instanceof AssistantMessage am && am.getText().contains("Server time is"));
    assertThat(turn2Prompt.get(turn2Prompt.size() - 1))
        .isInstanceOfSatisfying(
            UserMessage.class, m -> assertThat(m.getText()).contains("Which timezone"));
  }

  @Test
  void streamingEmitsPartialEvents() {
    createSession("s2");

    List<Event> events =
        run(
            "s2",
            "Please stream a greeting.",
            RunConfig.builder().streamingMode(RunConfig.StreamingMode.SSE).build());

    List<String> chunks =
        events.stream().map(SpikeAdapterTest::eventText).filter(t -> !t.isEmpty()).toList();
    long partials = events.stream().filter(e -> e.partial().orElse(false)).count();

    assertThat(partials).as("incremental delta events").isGreaterThanOrEqualTo(2);
    assertThat(chunks).hasSize(3);
    assertThat(String.join("", chunks)).isEqualTo("Hello, spike.");
  }

  private void createSession(String sessionId) {
    sessions.createSession(APP, USER, Map.of(), sessionId).blockingGet();
  }

  private List<Event> run(String sessionId, String message, RunConfig config) {
    return runner
        .runAsync(USER, sessionId, Content.fromParts(Part.fromText(message)), config)
        .toList()
        .blockingGet();
  }

  private static List<String> toolNames(Prompt prompt) {
    if (prompt.getOptions() instanceof ToolCallingChatOptions options) {
      return options.getToolCallbacks().stream()
          .map(cb -> cb.getToolDefinition().name())
          .toList();
    }
    return List.of();
  }

  private static boolean sawFunctionCall(List<Event> events) {
    return events.stream()
        .anyMatch(
            e ->
                e.content()
                    .flatMap(Content::parts)
                    .map(parts -> parts.stream().anyMatch(p -> p.functionCall().isPresent()))
                    .orElse(false));
  }

  private static String eventText(Event e) {
    return e.content().map(Content::text).orElse("");
  }

  private static String eventsText(List<Event> events) {
    return events.stream().map(SpikeAdapterTest::eventText).reduce("", String::concat);
  }

  static final class FakeChatModel implements ChatModel {

    final List<Prompt> prompts = new CopyOnWriteArrayList<>();

    @Override
    public ChatResponse call(Prompt prompt) {
      prompts.add(prompt);
      List<Message> messages = prompt.getInstructions();
      Message last = messages.get(messages.size() - 1);

      if (last instanceof ToolResponseMessage toolResponse) {
        String data = toolResponse.getResponses().get(0).responseData();
        return text("Server time is " + data);
      }
      if (last instanceof UserMessage user
          && user.getText().toLowerCase().contains("which timezone")) {
        return text("UTC.");
      }
      return toolCall(toolNames(prompt).get(0));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
      prompts.add(prompt);
      return Flux.just(chunk("Hello"), chunk(", "), chunk("spike."));
    }

    private static ChatResponse toolCall(String toolName) {
      AssistantMessage message =
          AssistantMessage.builder()
              .content("")
              .toolCalls(
                  List.of(
                      new AssistantMessage.ToolCall(
                          "call_1", "function", toolName, "{\"timezone\":\"UTC\"}")))
              .build();
      return new ChatResponse(List.of(new Generation(message)));
    }

    private static ChatResponse text(String text) {
      return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static ChatResponse chunk(String text) {
      return text(text);
    }
  }
}
