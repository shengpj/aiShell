package com.aishell.chat;

import com.aishell.terminal.TerminalSessions;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class ChatController {

  private final ChatService chats;
  private final TerminalSessions terminals;
  private final ConversationStore conversations;

  public ChatController(
      ChatService chats, TerminalSessions terminals, ConversationStore conversations) {
    this.chats = chats;
    this.terminals = terminals;
    this.conversations = conversations;
  }

  @PostMapping(value = "/api/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter chat(@RequestBody(required = false) ChatRequest request) {
    if (request == null || request.session() == null || request.session().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "session is required");
    }
    if (request.message() == null || request.message().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
    }
    if (terminals.findOpen(request.session()).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "terminal session is not connected");
    }
    String conversation = conversationOf(request.session());
    return chats.start(conversation, request.session(), request.message().trim(), request.turn());
  }

  @PostMapping("/api/chat/delete")
  public Map<String, Object> delete(@RequestBody(required = false) DeleteRequest request) {
    if (request == null || request.session() == null || request.session().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "session is required");
    }
    if (request.turn() == null || request.turn().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "turn is required");
    }
    if (terminals.findOpen(request.session()).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "terminal session is not connected");
    }
    if (chats.isRunning(request.session())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "a chat turn is already running");
    }
    String conversation = conversationOf(request.session());
    return chats.deleteTurn(conversation, request.session(), request.turn().trim());
  }

  @GetMapping("/api/chat/history")
  public ChatHistory history(
      @RequestParam(name = "session", required = false) String session,
      @RequestParam(name = "limit", required = false) Integer limit) {
    if (session == null || session.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "session is required");
    }
    if (terminals.findOpen(session).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "terminal session is not connected");
    }
    int capped = limit == null ? 200 : Math.max(1, Math.min(limit, 1000));
    String conversation = conversationOf(session);
    List<Map<String, Object>> entries = chats.history(conversation, capped);
    boolean truncated = chats.historyCount(conversation) > entries.size();
    return new ChatHistory(conversation, entries, truncated);
  }

  @PostMapping("/api/chat/reset")
  public Map<String, Object> reset(@RequestBody(required = false) ResetRequest request) {
    if (request == null || request.session() == null || request.session().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "session is required");
    }
    if (terminals.findOpen(request.session()).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "terminal session is not connected");
    }
    if (chats.isRunning(request.session())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "a chat turn is already running");
    }
    String conversation = conversations.next(hostKeyOf(request.session()));
    return Map.of("conversation", conversation);
  }

  @PostMapping("/api/agent/stop")
  public Map<String, Object> stop() {
    return Map.of("stopped", terminals.stopAllAi());
  }

  private String hostKeyOf(String session) {
    return terminals.conversationKey(session).orElse(session);
  }

  private String conversationOf(String session) {
    return conversations.current(hostKeyOf(session));
  }

  public record ChatRequest(String session, String message, String turn) {}

  public record DeleteRequest(String session, String turn) {}

  public record ResetRequest(String session) {}

  public record ChatHistory(
      String conversation, List<Map<String, Object>> entries, boolean truncated) {}
}
