package com.aishell.terminal;

/** Receives commands the user typed in a hooked shell, after the command finished (ADR 0010). */
public interface UserCommandListener {

  void onUserCommand(String sessionId, String command, int exitCode, String cwd);
}
