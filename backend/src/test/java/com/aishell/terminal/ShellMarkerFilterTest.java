package com.aishell.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShellMarkerFilterTest {

  private final ShellMarkerFilter filter = new ShellMarkerFilter();
  private final List<ShellHook.Marker> markers = new ArrayList<>();
  private final ByteArrayOutputStream visible = new ByteArrayOutputStream();

  @Test
  void stripsMarkersAndKeepsTextAndForeignEscapes() {
    feed("ab\u001b[31mred\u001b[0m" + marker("H", "bash") + "cd\n");

    assertThat(text()).isEqualTo("ab\u001b[31mred\u001b[0mcd\n");
    assertThat(markers).containsExactly(new ShellHook.Marker("H", "bash"));
  }

  @Test
  void reassemblesAMarkerSplitAcrossChunks() {
    byte[] all = ("x" + marker("D", "0;L3RtcA==;bHM=") + "y").getBytes(StandardCharsets.UTF_8);
    for (byte b : all) {
      feed(new byte[] {b});
    }

    assertThat(text()).isEqualTo("xy");
    assertThat(markers).containsExactly(new ShellHook.Marker("D", "0;L3RtcA==;bHM="));
  }

  @Test
  void supportsTheStringTerminator() {
    feed("q\u001b]633;AISHELL;H;zsh\u001b\\r");

    assertThat(text()).isEqualTo("qr");
    assertThat(markers).containsExactly(new ShellHook.Marker("H", "zsh"));
  }

  @Test
  void passesThroughAPartialPrefixThatNeverCompletes() {
    feed("abc\u001b]");
    assertThat(text()).isEqualTo("abc");

    feed("x");
    assertThat(text()).isEqualTo("abc\u001b]x");
    assertThat(markers).isEmpty();
  }

  @Test
  void reportsACommandMarkerWithItsPayload() {
    feed(marker("D", "1;L3RtcA==;ZWNobyBoaQ=="));

    assertThat(text()).isEmpty();
    assertThat(markers).containsExactly(new ShellHook.Marker("D", "1;L3RtcA==;ZWNobyBoaQ=="));
  }

  @Test
  void flushesAnUnterminatedMarkerWhenItGrowsTooLarge() {
    feed("a\u001b]633;AISHELL;D;");
    feed("b".repeat(9_000));

    assertThat(text()).isEqualTo("a\u001b]633;AISHELL;D;" + "b".repeat(9_000));
    assertThat(markers).isEmpty();
  }

  private void feed(String text) {
    feed(text.getBytes(StandardCharsets.UTF_8));
  }

  private void feed(byte[] bytes) {
    visible.writeBytes(filter.filter(bytes, markers::add));
  }

  private String text() {
    return visible.toString(StandardCharsets.UTF_8);
  }

  private static String marker(String kind, String payload) {
    return "\u001b]633;AISHELL;" + kind + ";" + payload + "\u0007";
  }
}
