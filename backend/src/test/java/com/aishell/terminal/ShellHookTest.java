package com.aishell.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class ShellHookTest {

  @Test
  void recognisesBashAndZshPaths() {
    assertThat(ShellHook.shellOf("/bin/bash")).isEqualTo(ShellHook.Shell.BASH);
    assertThat(ShellHook.shellOf("-bash")).isEqualTo(ShellHook.Shell.BASH);
    assertThat(ShellHook.shellOf("bash")).isEqualTo(ShellHook.Shell.BASH);
    assertThat(ShellHook.shellOf("/usr/bin/zsh")).isEqualTo(ShellHook.Shell.ZSH);
    assertThat(ShellHook.shellOf("/bin/fish")).isNull();
    assertThat(ShellHook.shellOf("")).isNull();
    assertThat(ShellHook.shellOf(null)).isNull();
  }

  @Test
  void bashPayloadIsBase64OfTheHookScript() {
    String payload = ShellHook.payload(ShellHook.Shell.BASH);
    String script = decoded(payload);

    assertThat(script).isEqualTo(ShellHook.script(ShellHook.Shell.BASH));
    assertThat(script)
        .contains("PROMPT_COMMAND")
        .contains("builtin history 1")
        .contains("history -d")
        .contains("__aishell_emit H bash")
        .contains("__aishell_emit D")
        .contains("__aishell_emit W")
        .contains("shopt -qo history")
        .contains("HISTCONTROL");
  }

  @Test
  void zshPayloadUsesPrecmdAndPreexecHooks() {
    String script = decoded(ShellHook.payload(ShellHook.Shell.ZSH));

    assertThat(script).contains("precmd_functions").contains("preexec_functions");
    assertThat(script).contains("__aishell_emit H zsh");
    assertThat(script).as("zsh captures every command through preexec").doesNotContain("__aishell_emit W");
  }

  @Test
  void payloadsStayBelowThePtyCanonicalLineLimit() {
    assertThat(ShellHook.payload(ShellHook.Shell.BASH).length())
        .isLessThan(ShellHook.MAX_PAYLOAD_CHARS);
    assertThat(ShellHook.payload(ShellHook.Shell.ZSH).length())
        .isLessThan(ShellHook.MAX_PAYLOAD_CHARS);
  }

  @Test
  void installerReadsThePayloadSilentlyAndHidesItsOwnLine() {
    assertThat(ShellHook.INSTALL_LINE)
        .contains("read -rs __p")
        .contains("base64 -d")
        .contains("\\033[1A\\033[2K\\r")
        .doesNotContain("history");
  }

  private static String decoded(String payload) {
    return new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8);
  }
}
