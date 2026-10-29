package com.aishell.terminal;

import java.util.regex.Pattern;

/** Removes ANSI escapes so captured output can be read by the model and matched against prompts. */
final class AnsiText {

  private static final Pattern OSC =
      Pattern.compile("\u001B\\][^\u0007]*(?:\u0007|\u001B\\\\)");
  private static final Pattern CSI = Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]");
  private static final Pattern ESC_SINGLE = Pattern.compile("\u001B[@-_]");

  private AnsiText() {}

  static String strip(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    String withoutOsc = OSC.matcher(text).replaceAll("");
    String withoutCsi = CSI.matcher(withoutOsc).replaceAll("");
    return ESC_SINGLE.matcher(withoutCsi).replaceAll("");
  }
}
