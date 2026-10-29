package com.aishell.agent;

import com.aishell.terminal.AiCommandResult;
import com.aishell.terminal.TerminalBridge;
import com.aishell.terminal.TerminalSessions;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** ADK function tool that runs one shell command in the session's shared PTY. */
public final class RunCommandTool {

  private final String sessionId;
  private final TerminalSessions sessions;
  private final AiCommandGuard guard;
  private final AuditLog audit;
  private final ChatChannel channel;
  private final Duration timeout;

  RunCommandTool(
      String sessionId,
      TerminalSessions sessions,
      AiCommandGuard guard,
      AuditLog audit,
      ChatChannel channel,
      Duration timeout) {
    this.sessionId = sessionId;
    this.sessions = sessions;
    this.guard = guard;
    this.audit = audit;
    this.channel = channel;
    this.timeout = timeout;
  }

  public Map<String, Object> runCommand(String command) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("command", command);

    Optional<String> rejection = guard.reject(command);
    if (rejection.isPresent()) {
      String reason = rejection.get();
      audit.recordRefusal(sessionId, command, reason);
      channel.send("command", Map.of("state", "refused", "command", command, "reason", reason));
      result.put("status", "refused");
      result.put("reason", reason);
      return result;
    }

    TerminalBridge bridge = sessions.findOpen(sessionId).orElse(null);
    if (bridge == null) {
      String reason = "the terminal is not connected";
      audit.recordRefusal(sessionId, command, reason);
      channel.send("command", Map.of("state", "refused", "command", command, "reason", reason));
      result.put("status", "unavailable");
      result.put("reason", reason);
      return result;
    }

    channel.send(
        "command",
        Map.of("state", "started", "command", command, "timeoutSeconds", timeout.toSeconds()));

    AiCommandResult outcome = bridge.runAiCommand(command, timeout);
    audit.recordCommand(sessionId, command, outcome);

    Map<String, Object> finished = new LinkedHashMap<>();
    finished.put("state", "finished");
    finished.put("command", command);
    finished.put("status", outcome.status().name().toLowerCase());
    finished.put("exitCode", outcome.exitCode());
    finished.put("durationMs", outcome.durationMs());
    if (outcome.message() != null) {
      finished.put("message", outcome.message());
    }
    channel.send("command", finished);

    result.put("status", outcome.status().name().toLowerCase());
    result.put("exitCode", outcome.exitCode());
    result.put("durationMs", outcome.durationMs());
    if (!outcome.output().isEmpty()) {
      result.put("output", outcome.output());
    }
    if (outcome.message() != null) {
      result.put("message", outcome.message());
    }
    return result;
  }
}
