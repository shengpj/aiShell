package com.aishell.chat;

import com.aishell.terminal.TerminalSessions;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class CommandHistoryController {

  private final AuditLog audit;
  private final TerminalSessions terminals;

  public CommandHistoryController(AuditLog audit, TerminalSessions terminals) {
    this.audit = audit;
    this.terminals = terminals;
  }

  @GetMapping("/api/commands/history")
  public CommandHistory history(
      @RequestParam(name = "session", required = false) String session,
      @RequestParam(name = "query", required = false) String query,
      @RequestParam(name = "limit", required = false) Integer limit) {
    if (session == null || session.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "session is required");
    }
    if (terminals.findOpen(session).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "terminal session is not connected");
    }
    int capped = limit == null ? 100 : Math.max(1, Math.min(limit, 500));
    AuditLog.History result = audit.history(session, query, capped);
    return new CommandHistory(result.entries(), result.truncated());
  }

  public record CommandHistory(List<Map<String, Object>> entries, boolean truncated) {}
}
