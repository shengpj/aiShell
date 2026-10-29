package com.aishell.terminal;

/** Outcome of one AI command executed in the shared PTY. */
public record AiCommandResult(
    Status status, int exitCode, String output, String message, long durationMs) {

  public enum Status {
    COMPLETED,
    REFUSED,
    TIMEOUT,
    CANCELLED,
    UNAVAILABLE
  }

  static AiCommandResult completed(int exitCode, String output, long durationMs) {
    return new AiCommandResult(Status.COMPLETED, exitCode, output, null, durationMs);
  }

  static AiCommandResult refused(String message, long durationMs) {
    return new AiCommandResult(Status.REFUSED, -1, "", message, durationMs);
  }

  static AiCommandResult unavailable(String message, long durationMs) {
    return new AiCommandResult(Status.UNAVAILABLE, -1, "", message, durationMs);
  }
}
