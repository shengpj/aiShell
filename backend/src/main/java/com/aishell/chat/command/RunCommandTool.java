package com.aishell.chat.command;

import com.aishell.chat.AuditLog;
import com.aishell.chat.ChatChannel;
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
  private final Duration approvalTimeout;
  private final AiExecutionPolicy policy;
  private final CommandApproval approvals;

  public RunCommandTool(
      String sessionId,
      TerminalSessions sessions,
      AiCommandGuard guard,
      AuditLog audit,
      ChatChannel channel,
      Duration timeout,
      Duration approvalTimeout,
      AiExecutionPolicy policy,
      CommandApproval approvals) {
    this.sessionId = sessionId;
    this.sessions = sessions;
    this.guard = guard;
    this.audit = audit;
    this.channel = channel;
    this.timeout = timeout;
    this.approvalTimeout = approvalTimeout;
    this.policy = policy;
    this.approvals = approvals;
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

    if (policy == AiExecutionPolicy.EXPLAIN) {
      String reason = "command execution is disabled by the current policy; explain the command instead";
      audit.recordRefusal(sessionId, command, reason);
      channel.send("command", Map.of("state", "refused", "command", command, "reason", reason));
      result.put("status", "refused");
      result.put("reason", reason);
      return result;
    }

    String approvalToken = null;
    if (policy == AiExecutionPolicy.CONFIRM) {
      CommandApproval.Decision decision =
          approvals.await(sessionId, command, channel, approvalTimeout);
      approvalToken = decision.token();
      if (!decision.approved()) {
        audit.recordRefusal(sessionId, command, decision.reason());
        Map<String, Object> refused = new LinkedHashMap<>();
        refused.put("state", "refused");
        refused.put("command", command);
        refused.put("reason", decision.reason());
        refused.put("approvalToken", approvalToken);
        channel.send("command", refused);
        result.put("status", "refused");
        result.put("reason", decision.reason());
        return result;
      }
    }

    Map<String, Object> started = new LinkedHashMap<>();
    started.put("state", "started");
    started.put("command", command);
    started.put("timeoutSeconds", timeout.toSeconds());
    if (approvalToken != null) {
      started.put("approvalToken", approvalToken);
    }
    channel.send("command", started);

    AiCommandResult outcome = bridge.runAiCommand(command, timeout);
    audit.recordCommand(sessionId, command, outcome);

    Map<String, Object> finished = new LinkedHashMap<>();
    finished.put("state", "finished");
    finished.put("command", command);
    finished.put("status", outcome.status().name().toLowerCase());
    finished.put("exitCode", outcome.exitCode());
    finished.put("durationMs", outcome.durationMs());
    if (!outcome.output().isEmpty()) {
      finished.put("output", outcome.output());
    }
    if (approvalToken != null) {
      finished.put("approvalToken", approvalToken);
    }
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
