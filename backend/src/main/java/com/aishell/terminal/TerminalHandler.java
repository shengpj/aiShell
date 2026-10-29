package com.aishell.terminal;

import com.aishell.host.HostService;
import com.aishell.host.HostService.ConnectTarget;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class TerminalHandler extends BinaryWebSocketHandler {

  private final ObjectMapper json;
  private final Path knownHosts;
  private final TerminalSessions sessions;
  private final HostService hosts;
  private final UserCommandListener userCommands;
  private final Map<String, TerminalBridge> bridges = new ConcurrentHashMap<>();

  public TerminalHandler(
      ObjectMapper json,
      TerminalSessions sessions,
      HostService hosts,
      UserCommandListener userCommands,
      @Value("${ai-shell.data.dir:${user.home}/.aishell}") String dataDir) {
    this.json = json;
    this.sessions = sessions;
    this.hosts = hosts;
    this.userCommands = userCommands;
    this.knownHosts = Path.of(dataDir, "known_hosts");
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    session.setBinaryMessageSizeLimit(1024 * 1024);
    session.setTextMessageSizeLimit(64 * 1024);
    WebSocketSession safe =
        new ConcurrentWebSocketSessionDecorator(session, 5_000, 8 * 1024 * 1024);
    TerminalBridge bridge = new TerminalBridge(safe, knownHosts, json, userCommands);
    bridges.put(session.getId(), bridge);
    sessions.register(bridge);
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    TerminalBridge bridge = bridges.get(session.getId());
    if (bridge == null) {
      return;
    }
    JsonNode node;
    try {
      node = json.readTree(message.getPayload());
    } catch (JacksonException e) {
      bridge.sendError("invalid control message");
      return;
    }
    switch (node.path("type").asText("")) {
      case "open" -> open(bridge, node);
      case "resize" -> bridge.resize(node.path("cols").asInt(0), node.path("rows").asInt(0));
      case "takeover" -> bridge.requestTakeover(node.path("enabled").asBoolean(false));
      case "close" -> bridge.close();
      default -> bridge.sendError("unknown control type");
    }
  }

  private void open(TerminalBridge bridge, JsonNode node) {
    int cols = node.path("cols").asInt(80);
    int rows = node.path("rows").asInt(24);
    String profileId = node.path("profile").asText("");
    if (!profileId.isBlank()) {
      ConnectTarget target;
      try {
        target = hosts.resolve(profileId);
      } catch (ResponseStatusException e) {
        bridge.sendError(e.getReason() == null ? "profile is not available" : e.getReason());
        return;
      } catch (IllegalStateException e) {
        bridge.sendError(e.getMessage());
        return;
      }
      bridge.open(target, cols, rows);
      return;
    }
    bridge.open(
        new ConnectTarget(
            node.path("host").asText(""),
            node.path("port").asInt(22),
            node.path("user").asText(""),
            node.path("identity").asText(""),
            null),
        cols,
        rows);
  }

  @Override
  protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
    TerminalBridge bridge = bridges.get(session.getId());
    if (bridge == null) {
      return;
    }
    ByteBuffer payload = message.getPayload();
    byte[] data = new byte[payload.remaining()];
    payload.get(data);
    bridge.write(data);
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) {
    TerminalBridge bridge = bridges.get(session.getId());
    if (bridge != null) {
      bridge.close();
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    TerminalBridge bridge = bridges.remove(session.getId());
    if (bridge != null) {
      sessions.unregister(bridge);
      bridge.close();
    }
  }
}
