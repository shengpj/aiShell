package com.aishell.terminal;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * The shell-side command-audit hook (ADR 0010). Nothing is written to the target disk: the hook is
 * typed into the interactive shell, its source travels base64-encoded through {@code read -s} so
 * it is neither echoed nor stored in history, and the installer line deletes itself from bash
 * history afterwards.
 *
 * <p>Markers are OSC 633-style private sequences {@code ESC ] 633 ; AISHELL ; <kind> ; <payload>
 * BEL}:
 *
 * <ul>
 *   <li>{@code I} — the installer is blocked in {@code read}, the payload can be sent;
 *   <li>{@code H} — the hook is live (first prompt); payload = shell name;
 *   <li>{@code D} — a command finished; payload = {@code <exit>;<b64 cwd>;<b64 command>};
 *   <li>{@code W} — the capture warranty changed; payload = {@code history-off} or {@code
 *       ignorespace}, empty when the limitation is gone (bash only).
 * </ul>
 */
final class ShellHook {

  static final String MARKER_PREFIX = "\u001b]633;AISHELL;";
  static final byte[] MARKER_PREFIX_BYTES = MARKER_PREFIX.getBytes(StandardCharsets.US_ASCII);

  /** POSIX-shell-neutral installer; the payload is fed to {@code read -s} right after the I marker. */
  static final String INSTALL_LINE =
      "printf '\\033]633;AISHELL;I;\\007' >&2; read -rs __p; "
          + "eval \"$(printf %s \"$__p\" | base64 -d)\"; unset __p; "
          + "printf '\\033[1A\\033[2K\\r'";

  /** Linux canonical-mode line limit is 4096 bytes; keep the base64 payload well below it. */
  static final int MAX_PAYLOAD_CHARS = 3_800;

  private static final String BASH_SCRIPT =
      """
      __aishell_emit() { printf '\\033]633;AISHELL;%s;%s\\007' "$1" "$2" >&2; }
      __aishell_hnum() {
        local raw rest
        raw=$(HISTTIMEFORMAT= builtin history 1 2>/dev/null)
        rest="${raw#"${raw%%[! ]*}"}"
        printf %s "${rest%% *}"
      }
      __aishell_hook() {
        local __ec=$? __raw __rest __num __cmd __warn
        __warn=
        if ! shopt -qo history 2>/dev/null; then
          __warn=history-off
        else
          case "${HISTCONTROL:-}" in *ignorespace*|*ignoreboth*) __warn=ignorespace ;; esac
        fi
        __raw=$(HISTTIMEFORMAT= builtin history 1 2>/dev/null)
        __rest="${__raw#"${__raw%%[! ]*}"}"
        __num="${__rest%% *}"
        __cmd="${__rest#*"$__num"}"
        __cmd="${__cmd#  }"
        if [ -z "${__seen:-}" ]; then
          __seen=1
          __last=$__num
          __warn_last=$__warn
          __aishell_emit H bash
          [ -n "$__warn" ] && __aishell_emit W "$__warn"
          return
        fi
        if [ -n "${__num:-}" ] && [ "$__num" != "${__last:-}" ]; then
          __last=$__num
          __aishell_emit D "$__ec;$(printf %s "$PWD" | base64 | tr -d '\\n');$(printf %s "$__cmd" | base64 | tr -d '\\n')"
        fi
        if [ "$__warn" != "${__warn_last:-}" ]; then
          __warn_last=$__warn
          __aishell_emit W "$__warn"
        fi
      }
      if [ -z "${__installed:-}" ]; then
        __installed=1
        case "$(declare -p PROMPT_COMMAND 2>/dev/null)" in
          "declare -a"*) PROMPT_COMMAND=(__aishell_hook "${PROMPT_COMMAND[@]}") ;;
          *) if [ -n "${PROMPT_COMMAND:-}" ]; then PROMPT_COMMAND="__aishell_hook; $PROMPT_COMMAND"; else PROMPT_COMMAND=__aishell_hook; fi ;;
        esac
      fi
      __hn=$(__aishell_hnum)
      [ -n "$__hn" ] && builtin history -d "$__hn" 2>/dev/null
      true
      """;

  private static final String ZSH_SCRIPT =
      """
      __aishell_emit() { printf '\\033]633;AISHELL;%s;%s\\007' "$1" "$2" >&2; }
      __aishell_preexec() { __aishell_cmd=$1; __aishell_pending=1 }
      __aishell_precmd() {
        local __ec=$?
        if [ -z "${__aishell_seen:-}" ]; then
          __aishell_seen=1
          __aishell_emit H zsh
          return
        fi
        [ -z "${__aishell_pending:-}" ] && return
        __aishell_pending=
        __aishell_emit D "$__ec;$(printf %s "$PWD" | base64 | tr -d '\\n');$(printf %s "$__aishell_cmd" | base64 | tr -d '\\n')"
      }
      if [ -z "${__aishell_hook_installed:-}" ]; then
        __aishell_hook_installed=1
        precmd_functions=(__aishell_precmd ${precmd_functions[@]})
        preexec_functions=(__aishell_preexec ${preexec_functions[@]})
      fi
      true
      """;

  private ShellHook() {}

  record Marker(String kind, String payload) {}

  enum Shell {
    BASH("bash"),
    ZSH("zsh");

    private final String id;

    Shell(String id) {
      this.id = id;
    }

    String id() {
      return id;
    }
  }

  /** Maps a probed shell name ({@code /bin/bash}, {@code -zsh}, ...) to a hook shell, or null. */
  static Shell shellOf(String probe) {
    if (probe == null) {
      return null;
    }
    String name = probe.trim();
    int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
    if (slash >= 0) {
      name = name.substring(slash + 1);
    }
    while (name.startsWith("-")) {
      name = name.substring(1);
    }
    name = name.toLowerCase(Locale.ROOT);
    if (name.equals("bash")) {
      return Shell.BASH;
    }
    if (name.equals("zsh")) {
      return Shell.ZSH;
    }
    return null;
  }

  static String script(Shell shell) {
    return shell == Shell.ZSH ? ZSH_SCRIPT : BASH_SCRIPT;
  }

  /** Base64 of the hook source; the installer decodes and evaluates it. */
  static String payload(Shell shell) {
    return Base64.getEncoder()
        .encodeToString(script(shell).getBytes(StandardCharsets.UTF_8));
  }

  static String payload(String shellName) {
    Shell shell = shellOf(shellName);
    return shell == null ? null : payload(shell);
  }
}
