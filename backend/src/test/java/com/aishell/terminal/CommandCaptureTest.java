package com.aishell.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CommandCaptureTest {

  private static final String TOKEN = "a1b2c3";

  @Test
  void capturesOutputBetweenMarkersAndIgnoresTheEchoedWrapper() {
    CommandCapture capture = new CommandCapture(TOKEN);

    capture.accept(
        text("bob@host:~$ printf '\\n<<AI_SHELL:" + TOKEN + ":BEGIN>>\\n'\r\n"));
    assertThat(capture.hasStarted()).isFalse();

    capture.accept(text("<<AI_SHELL:" + TOKEN + ":BEGIN>>\r\n"));
    capture.accept(text("line one\r\n"));
    capture.accept(text("line two\r\n<<AI_SHELL:" + TOKEN + ":END:3>>\r\n"));

    assertThat(capture.isComplete()).isTrue();
    assertThat(capture.exitCode()).isEqualTo(3);
    assertThat(capture.output())
        .contains("line one")
        .contains("line two")
        .doesNotContain("AI_SHELL")
        .doesNotContain("printf");
  }

  @Test
  void handlesMultibyteCharactersSplitAcrossChunks() {
    CommandCapture capture = new CommandCapture(TOKEN);
    capture.accept(text("<<AI_SHELL:" + TOKEN + ":BEGIN>>\n"));

    byte[] chinese = "中文输出\n".getBytes(StandardCharsets.UTF_8);
    capture.accept(java.util.Arrays.copyOf(chinese, 2));
    capture.accept(java.util.Arrays.copyOfRange(chinese, 2, chinese.length));

    assertThat(capture.output()).contains("中文输出");
  }

  @Test
  void stripsAnsiEscapes() {
    CommandCapture capture = new CommandCapture(TOKEN);
    capture.accept(text("<<AI_SHELL:" + TOKEN + ":BEGIN>>\n"));
    capture.accept(text("\u001B[31mred\u001B[0m and \u001B]0;title\u0007plain\n"));

    assertThat(capture.output()).isEqualTo("red and plain\n");
  }

  @Test
  void detectsPasswordAndConfirmationPrompts() {
    assertThat(promptAfter("[sudo] password for bob: ")).isTrue();
    assertThat(promptAfter("Password: ")).isTrue();
    assertThat(promptAfter("Are you sure you want to continue connecting (yes/no/[fingerprint])? "))
        .isTrue();
    assertThat(promptAfter("Overwrite local file? [y/n] ")).isTrue();
    assertThat(promptAfter("listing files\n")).isFalse();
    assertThat(promptAfter("password policy updated\n")).isFalse();
  }

  @Test
  void keepsHeadAndTailOfLongOutput() {
    CommandCapture capture = new CommandCapture(TOKEN);
    capture.accept(text("<<AI_SHELL:" + TOKEN + ":BEGIN>>\n"));
    String line = "x".repeat(200) + "\n";
    for (int i = 0; i < 200; i++) {
      capture.accept(text(line));
    }
    capture.accept(text("<<AI_SHELL:" + TOKEN + ":END:0>>\n"));

    String output = capture.output();
    assertThat(output).contains("characters truncated");
    assertThat(output.length()).isLessThan(20_000);
  }

  @Test
  void doesNotCompleteWhenTheEndMarkerBelongsToAnotherCommand() {
    CommandCapture capture = new CommandCapture(TOKEN);
    capture.accept(text("<<AI_SHELL:" + TOKEN + ":BEGIN>>\n"));
    capture.accept(text("<<AI_SHELL:deadbeef:END:0>>\n"));

    assertThat(capture.isComplete()).isFalse();
  }

  private static boolean promptAfter(String output) {
    CommandCapture capture = new CommandCapture(TOKEN);
    capture.accept(text("<<AI_SHELL:" + TOKEN + ":BEGIN>>\n"));
    capture.accept(text(output));
    return capture.looksLikePrompt();
  }

  private static byte[] text(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
