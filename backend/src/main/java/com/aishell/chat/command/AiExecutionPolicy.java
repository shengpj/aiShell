package com.aishell.chat.command;

import java.util.Locale;

/** Controls whether an AI turn may write commands to the shared terminal. */
public enum AiExecutionPolicy {
  AUTO,
  CONFIRM,
  EXPLAIN;

  public static AiExecutionPolicy parse(String value, AiExecutionPolicy fallback) {
    if (value == null || value.isBlank()) {
      return fallback == null ? AUTO : fallback;
    }
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ignored) {
      return fallback == null ? AUTO : fallback;
    }
  }
}
