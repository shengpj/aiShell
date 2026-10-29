package com.aishell.chat.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Deny-list of destructive commands checked before anything is written to the PTY (ADR 0002). */
@Component
public class AiCommandGuard {

  private static final Logger log = LoggerFactory.getLogger(AiCommandGuard.class);
  private static final int MAX_COMMAND_LENGTH = 4_000;

  private final List<Pattern> denyPatterns = new ArrayList<>();

  public AiCommandGuard(AiProperties properties) {
    for (String pattern : properties.getDenyPatterns()) {
      if (pattern == null || pattern.isBlank()) {
        continue;
      }
      try {
        denyPatterns.add(Pattern.compile(pattern));
      } catch (PatternSyntaxException e) {
        log.warn("ignoring invalid deny pattern '{}': {}", pattern, e.getMessage());
      }
    }
  }

  public Optional<String> reject(String command) {
    if (command == null || command.isBlank()) {
      return Optional.of("empty command");
    }
    if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
      return Optional.of("multi-line commands are not supported; send one command at a time");
    }
    if (command.indexOf('\0') >= 0) {
      return Optional.of("NUL bytes are not allowed in commands");
    }
    if (command.length() > MAX_COMMAND_LENGTH) {
      return Optional.of("command is longer than " + MAX_COMMAND_LENGTH + " characters");
    }
    for (Pattern pattern : denyPatterns) {
      if (pattern.matcher(command).find()) {
        return Optional.of("blocked by the destructive-command deny-list");
      }
    }
    return Optional.empty();
  }
}
