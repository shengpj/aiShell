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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = "ai-shell.token=test-token")
@Import(SpikeAgentConfig.class)
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = "\\S+")
class DeepSeekSpikeTest {

  private static final String APP = "spike-live";
  private static final String USER = "user";

  @Autowired SpringAI springAI;
  @Autowired LlmAgent spikeAgent;

  private InMemorySessionService sessions;
  private Runner runner;

  @BeforeEach
  void setUp() {
    sessions = new InMemorySessionService();
    runner = Runner.builder().agent(spikeAgent).appName(APP).sessionService(sessions).build();
  }

  @Test
  void toolCallAndMultiTurn() {
    sessions.createSession(APP, USER, Map.of(), "live-tool").blockingGet();

    List<Event> first =
        run(
            "live-tool",
            "What is the current time in UTC? You must use the getServerTime tool.",
            RunConfig.builder().build());

    String firstText = eventsText(first);
    System.out.println("live turn1: " + firstText);
    assertThat(sawFunctionCall(first)).as("ADK observed a function call").isTrue();
    assertThat(firstText).containsPattern("\\d{4}-\\d{2}-\\d{2}T");

    List<Event> second =
        run("live-tool", "Which timezone did you just check?", RunConfig.builder().build());

    String secondText = eventsText(second);
    System.out.println("live turn2: " + secondText);
    assertThat(secondText.toUpperCase()).contains("UTC");
  }

  @Test
  void streaming() {
    sessions.createSession(APP, USER, Map.of(), "live-stream").blockingGet();

    List<Event> events =
        run(
            "live-stream",
            "Reply with exactly this text and nothing else: STEP ONE. STEP TWO.",
            RunConfig.builder().streamingMode(RunConfig.StreamingMode.SSE).build());

    long partials = events.stream().filter(e -> e.partial().orElse(false)).count();
    String text = eventsText(events);
    System.out.println("live stream partial events: " + partials + " text: " + text);

    assertThat(text).contains("STEP ONE").contains("STEP TWO");
    assertThat(partials).as("streaming produced incremental chunks").isGreaterThanOrEqualTo(1);
  }

  private List<Event> run(String sessionId, String message, RunConfig config) {
    return runner
        .runAsync(USER, sessionId, Content.fromParts(Part.fromText(message)), config)
        .toList()
        .blockingGet();
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

  private static String eventsText(List<Event> events) {
    return events.stream()
        .map(e -> e.content().map(Content::text).orElse(""))
        .reduce("", String::concat);
  }
}
