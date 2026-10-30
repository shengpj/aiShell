package com.aishell.chat.command;

import com.aishell.chat.ChatChannel;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/** In-memory approval gate for one command at a time in a confirm-mode AI turn. */
@Component
public final class CommandApproval {

  private final Map<String, Pending> pending = new ConcurrentHashMap<>();

  public Decision await(String sessionId, String command, ChatChannel channel, Duration timeout) {
    String token = UUID.randomUUID().toString();
    CompletableFuture<Decision> result = new CompletableFuture<>();
    pending.put(token, new Pending(sessionId, result));
    channel.send(
        "command",
        Map.of(
            "state", "pending",
            "command", command,
            "approvalToken", token,
            "approvalTimeoutSeconds", timeout.toSeconds()));
    try {
      return result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      return new Decision(false, "confirmation timed out", token);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Decision(false, "confirmation interrupted", token);
    } catch (Exception e) {
      return new Decision(false, "confirmation failed", token);
    } finally {
      pending.remove(token);
    }
  }

  public boolean resolve(String token, boolean approved) {
    if (token == null || token.isBlank()) {
      return false;
    }
    Pending item = pending.get(token);
    return item != null
        && item.result().complete(new Decision(approved, approved ? "approved" : "rejected by user", token));
  }

  public void cancelSession(String sessionId) {
    if (sessionId == null) {
      return;
    }
    pending.forEach(
        (token, item) -> {
          if (sessionId.equals(item.sessionId())) {
            item.result().complete(new Decision(false, "stopped by user", token));
          }
        });
  }

  public void cancelAll() {
    pending.forEach(
        (token, item) -> item.result().complete(new Decision(false, "stopped by user", token)));
  }

  private record Pending(String sessionId, CompletableFuture<Decision> result) {}

  public record Decision(boolean approved, String reason, String token) {}
}
