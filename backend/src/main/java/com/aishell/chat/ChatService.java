package com.aishell.chat;

import com.aishell.chat.command.AiCommandGuard;
import com.aishell.chat.command.AiProperties;
import com.aishell.chat.command.RunCommandTool;
import com.aishell.terminal.TerminalBridge;
import com.aishell.terminal.TerminalSessions;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.models.springai.SpringAI;
import com.google.adk.runner.Runner;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.Disposable;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs one ADK agent turn per chat request. The agent gets a {@link RunCommandTool} bound to the
 * requesting terminal session; events are streamed to the webview over SSE.
 */
@Service
public class ChatService {

  private static final Logger log = LoggerFactory.getLogger(ChatService.class);
  private static final String APP_NAME = "ai-shell";
  private static final String USER_ID = "local";
  private static final String INSTRUCTION =
      """
      You are an operations assistant inside an SSH terminal.
      Use the runCommand tool to run shell commands on the user's connected host.

      Rules:
      - Run one command per call, then read its exit code and output before deciding the next step.
      - Briefly state what you are about to do before calling runCommand, and summarize the result after.
      - Never invent command output. If output is truncated, say so.
      - status "refused" means the command was blocked or the terminal is busy; do not retry it.
      - status "timeout" or "cancelled" means the command was interrupted; stop and report.
      - The terminal is shared with the user and is fully interactive, so avoid full-screen programs such as
        vim, top or less.
      - If a command prompts for a password, passphrase or confirmation, run it unchanged: the prompt is
        shown to the user, who types the answer in the terminal and the call resumes afterwards.
      - Never redirect standard input (for example with </dev/null) or pipe in invented values to get past a prompt.
      - Answer the latest user message. Do not repeat or summarize commands from earlier turns unless the user asks about them.
      - A limit given in an earlier turn (for example "do not run anything") applies only to that turn; the latest message decides what to do now.
      - Answer in the user's language.
      """;

  private final SpringAI model;
  private final TerminalSessions terminals;
  private final AiCommandGuard guard;
  private final AuditLog audit;
  private final ObjectMapper json;
  private final AiProperties properties;
  private final SqliteAgentSessionService sessions;
  private final ChatTranscriptStore transcript;
  private final Set<String> active = ConcurrentHashMap.newKeySet();

  public ChatService(
      SpringAI model,
      TerminalSessions terminals,
      AiCommandGuard guard,
      AuditLog audit,
      ObjectMapper json,
      AiProperties properties,
      SqliteAgentSessionService sessions,
      ChatTranscriptStore transcript) {
    this.model = model;
    this.terminals = terminals;
    this.guard = guard;
    this.audit = audit;
    this.json = json;
    this.properties = properties;
    this.sessions = sessions;
    this.transcript = transcript;
  }

  /** True while a turn is streaming for this terminal session. */
  public boolean isRunning(String sessionId) {
    return active.contains(sessionId);
  }

  public SseEmitter start(String conversationId, String sessionId, String message, String turn) {
    if (!active.add(sessionId)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "a chat turn is already running for this session");
    }

    SseEmitter emitter = new SseEmitter(properties.getTurnTimeoutSeconds() * 1000L);
    CompletableFuture<Void> finished = new CompletableFuture<>();
    AtomicReference<Disposable> subscription = new AtomicReference<>();
    java.util.concurrent.atomic.AtomicBoolean turnDone =
        new java.util.concurrent.atomic.AtomicBoolean();

    Runnable onExternalStop =
        () -> {
          if (turnDone.compareAndSet(false, true)) {
            cancelTurn(sessionId, finished, subscription);
          }
        };

    String turnId =
        turn == null || turn.isBlank() ? java.util.UUID.randomUUID().toString() : turn.trim();
    recordUser(conversationId, turnId, message);
    ChatChannel channel =
        new ChatChannel(
            emitter,
            json,
            onExternalStop,
            (event, data) -> record(conversationId, turnId, event, data));

    emitter.onTimeout(
        () -> {
          channel.send("error", Map.of("message", "chat turn timed out"));
          onExternalStop.run();
          channel.complete();
        });
    emitter.onError(
        error -> {
          log.debug("chat SSE error for {}: {}", sessionId, error.getMessage());
          onExternalStop.run();
        });
    emitter.onCompletion(onExternalStop);

    Thread.ofVirtual()
        .name("chat-" + sessionId)
        .start(
            () ->
                runTurn(
                    conversationId, sessionId, message, channel, finished, subscription, turnDone));
    return emitter;
  }

  public List<Map<String, Object>> history(String conversationId) {
    return transcript.history(conversationId);
  }

  public List<Map<String, Object>> history(String conversationId, int limit) {
    return transcript.history(conversationId, limit);
  }

  public int historyCount(String conversationId) {
    return transcript.count(conversationId);
  }

  /**
   * Removes one question and everything the agent answered in that turn, from both the visible
   * transcript and the ADK event log, so the model no longer receives it as context.
   */
  public Map<String, Object> deleteTurn(String conversationId, String sessionId, String turnId) {
    if (isRunning(sessionId)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "a chat turn is already running for this session");
    }
    ChatTranscriptStore.TurnWindow window = transcript.turnWindow(conversationId, turnId);
    if (window == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "chat turn not found");
    }
    int messages = transcript.deleteTurn(conversationId, turnId);
    int events = sessions.deleteEventsBetween(conversationId, window.start(), window.end());
    log.info(
        "deleted chat turn {} of {}: {} messages, {} agent events", turnId, conversationId, messages, events);
    return Map.of("turn", turnId, "messages", messages, "events", events);
  }

  private void recordUser(String conversationId, String turnId, String message) {
    try {
      transcript.recordUser(conversationId, turnId, message);
    } catch (RuntimeException e) {
      log.warn("failed to record the user message: {}", e.getMessage());
    }
  }

  private void record(String conversationId, String turnId, String event, Object data) {
    if (!(data instanceof Map<?, ?> raw)) {
      return;
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    raw.forEach(
        (key, value) -> {
          if (key instanceof String name) {
            payload.put(name, value);
          }
        });
    try {
      switch (event) {
        case "text" -> {
          if (payload.get("text") instanceof String text && !text.isEmpty()) {
            transcript.recordAiText(conversationId, turnId, text);
          }
        }
        case "command" -> transcript.recordCommand(conversationId, turnId, payload);
        case "error" -> {
          if (payload.get("message") instanceof String text) {
            transcript.recordError(conversationId, turnId, text);
          }
        }
        default -> {
          // done and other events have no transcript row
        }
      }
    } catch (RuntimeException e) {
      log.warn("failed to record a chat event: {}", e.getMessage());
    }
  }

  private void cancelTurn(
      String sessionId, CompletableFuture<Void> finished, AtomicReference<Disposable> subscription) {
    finished.complete(null);
    Disposable disposable = subscription.getAndSet(null);
    if (disposable != null) {
      disposable.dispose();
    }
    terminals.findOpen(sessionId).ifPresent(TerminalBridge::cancelAiCommand);
  }

  private void runTurn(
      String conversationId,
      String sessionId,
      String message,
      ChatChannel channel,
      CompletableFuture<Void> finished,
      AtomicReference<Disposable> subscription,
      java.util.concurrent.atomic.AtomicBoolean turnDone) {
    try {
      if (finished.isDone()) {
        return;
      }
      ensureSession(conversationId);

      RunCommandTool tool =
          new RunCommandTool(
              sessionId,
              terminals,
              guard,
              audit,
              channel,
              Duration.ofSeconds(properties.getCommandTimeoutSeconds()));
      LlmAgent agent =
          LlmAgent.builder()
              .name("ops_assistant")
              .description("Runs shell commands in the user's SSH session.")
              .model(new TurnFinishingModel(model))
              .instruction(INSTRUCTION)
              .tools(FunctionTool.create(tool, "runCommand"))
              .build();
      Runner runner =
          Runner.builder().agent(agent).appName(APP_NAME).sessionService(sessions).build();

      Flowable<Event> events =
          runner.runAsync(
              USER_ID,
              conversationId,
              Content.fromParts(Part.fromText(message)),
              RunConfig.builder().streamingMode(RunConfig.StreamingMode.SSE).build());

      Disposable disposable =
          events.subscribe(
              event -> forward(event, channel),
              error -> {
                log.info("chat turn failed for {}: {}", sessionId, error.toString());
                channel.send("error", Map.of("message", messageOf(error)));
                finished.complete(null);
              },
              () -> {
                channel.send("done", Map.of());
                finished.complete(null);
              });
      subscription.set(disposable);

      finished.get(properties.getTurnTimeoutSeconds(), TimeUnit.SECONDS);
    } catch (java.util.concurrent.TimeoutException e) {
      channel.send("error", Map.of("message", "chat turn timed out"));
    } catch (Exception e) {
      log.info("chat turn aborted for {}: {}", sessionId, e.toString());
      channel.send("error", Map.of("message", messageOf(e)));
    } finally {
      Disposable disposable = subscription.getAndSet(null);
      if (disposable != null) {
        disposable.dispose();
      }
      turnDone.set(true);
      active.remove(sessionId);
      channel.complete();
      finished.complete(null);
    }
  }

  private void forward(Event event, ChatChannel channel) {
    event
        .errorMessage()
        .ifPresent(message -> channel.send("error", Map.of("message", message)));
    event
        .content()
        .map(Content::text)
        .filter(text -> !text.isEmpty())
        .ifPresent(text -> channel.send("text", Map.of("text", text)));
  }

  private void ensureSession(String conversationId) {
    Object existing =
        sessions
            .getSession(APP_NAME, USER_ID, conversationId, Optional.empty())
            .blockingGet();
    if (existing == null) {
      sessions.createSession(APP_NAME, USER_ID, Map.of(), conversationId).blockingGet();
    }
  }

  private static String messageOf(Throwable error) {
    String message = error.getMessage();
    return message == null || message.isBlank()
        ? error.getClass().getSimpleName()
        : message;
  }
}
