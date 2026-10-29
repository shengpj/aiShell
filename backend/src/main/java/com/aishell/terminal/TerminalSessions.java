package com.aishell.terminal;

import com.jcraft.jsch.Session;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Live terminal sessions, addressable by the id handed to the webview in the {@code opened} frame. */
@Component
public class TerminalSessions {

  private final Map<String, TerminalBridge> bridges = new ConcurrentHashMap<>();

  public void register(TerminalBridge bridge) {
    bridges.put(bridge.id(), bridge);
  }

  public void unregister(TerminalBridge bridge) {
    bridges.remove(bridge.id(), bridge);
  }

  public Optional<TerminalBridge> findOpen(String sessionId) {
    if (sessionId == null) {
      return Optional.empty();
    }
    TerminalBridge bridge = bridges.get(sessionId);
    return bridge != null && bridge.isOpen() ? Optional.of(bridge) : Optional.empty();
  }

  /** Host-derived conversation id for the live session, used to persist AI chat per host. */
  public Optional<String> conversationKey(String sessionId) {
    if (sessionId == null) {
      return Optional.empty();
    }
    TerminalBridge bridge = bridges.get(sessionId);
    String key = bridge == null ? null : bridge.conversationKey();
    return key == null || key.isBlank() ? Optional.empty() : Optional.of(key);
  }

  /** The live SSH session for file browsing; empty when the terminal is gone or disconnected. */
  public Optional<Session> findSshSession(String sessionId) {
    if (sessionId == null) {
      return Optional.empty();
    }
    TerminalBridge bridge = bridges.get(sessionId);
    Session session = bridge == null ? null : bridge.sshSession();
    return session != null && session.isConnected() ? Optional.of(session) : Optional.empty();
  }

  public int stopAllAi() {
    int stopped = 0;
    for (TerminalBridge bridge : bridges.values()) {
      if (bridge.cancelAiCommand()) {
        stopped++;
      }
    }
    return stopped;
  }
}
