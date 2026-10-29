package com.aishell.terminal;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Extracts the output of one AI command from the shared PTY byte stream.
 *
 * <p>The command is wrapped between two random marker lines. Because a PTY echoes the typed
 * wrapper, only lines identical to the begin marker and starting with the end marker are
 * accepted, which a random token makes collision-free in practice.
 */
final class CommandCapture {

  private static final int HEAD_LIMIT = 8 * 1024;
  private static final int TAIL_LIMIT = 8 * 1024;
  private static final int MAX_PARTIAL_LINE = 4 * 1024;

  private static final List<Pattern> PROMPT_PATTERNS =
      List.of(
          Pattern.compile("password[^:\\n]*:\\s*$"),
          Pattern.compile("\\(yes/no(\\[/fingerprint\\])?\\)\\??\\s*$"),
          Pattern.compile("\\[y/n\\]\\s*$"),
          Pattern.compile("are you sure you want to continue connecting"),
          Pattern.compile("passphrase[^:\\n]*:\\s*$"),
          Pattern.compile("press enter to continue"),
          Pattern.compile("(enter|type) .*(password|passphrase)"));

  private final String beginMarker;
  private final String endPrefix;
  private final CharsetDecoder decoder =
      StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPLACE)
          .onUnmappableCharacter(CodingErrorAction.REPLACE);

  private final StringBuilder pendingLine = new StringBuilder();
  private final java.io.ByteArrayOutputStream pendingBytes = new java.io.ByteArrayOutputStream();
  private final StringBuilder head = new StringBuilder();
  private final Deque<String> tail = new ArrayDeque<>();
  private int tailChars;
  private int droppedChars;

  private boolean started;
  private boolean complete;
  private int exitCode = -1;

  CommandCapture(String token) {
    this.beginMarker = "<<AI_SHELL:" + token + ":BEGIN>>";
    this.endPrefix = "<<AI_SHELL:" + token + ":END:";
  }

  String beginMarker() {
    return beginMarker;
  }

  String endPrefix() {
    return endPrefix;
  }

  synchronized void accept(byte[] data) {
    if (complete || data.length == 0) {
      return;
    }
    pendingBytes.write(data, 0, data.length);
    byte[] buffered = pendingBytes.toByteArray();
    ByteBuffer input = ByteBuffer.wrap(buffered);
    CharBuffer chars = CharBuffer.allocate(buffered.length + 4);
    decoder.decode(input, chars, false);
    pendingBytes.reset();
    if (input.hasRemaining()) {
      byte[] rest = new byte[input.remaining()];
      input.get(rest);
      pendingBytes.write(rest, 0, rest.length);
    }
    chars.flip();
    for (int i = 0; i < chars.length(); i++) {
      char c = chars.charAt(i);
      if (c == '\n') {
        handleLine(pendingLine.toString());
        pendingLine.setLength(0);
      } else if (c != '\r') {
        pendingLine.append(c);
      }
      if (pendingLine.length() > MAX_PARTIAL_LINE) {
        handleLine(pendingLine.toString());
        pendingLine.setLength(0);
      }
      if (complete) {
        return;
      }
    }
  }

  private void handleLine(String line) {
    if (!started) {
      if (line.equals(beginMarker)) {
        started = true;
      }
      return;
    }
    if (line.startsWith(endPrefix)) {
      int close = line.indexOf(">>", endPrefix.length());
      String code =
          close >= 0
              ? line.substring(endPrefix.length(), close)
              : line.substring(endPrefix.length());
      try {
        exitCode = Integer.parseInt(code.trim());
      } catch (NumberFormatException e) {
        exitCode = -1;
      }
      complete = true;
      return;
    }
    appendLine(line);
  }

  private void appendLine(String line) {
    String text = line + "\n";
    if (head.length() < HEAD_LIMIT) {
      head.append(text);
      return;
    }
    tail.addLast(text);
    tailChars += text.length();
    while (tailChars > TAIL_LIMIT && !tail.isEmpty()) {
      String removed = tail.removeFirst();
      tailChars -= removed.length();
      droppedChars += removed.length();
    }
  }

  synchronized boolean hasStarted() {
    return started;
  }

  synchronized boolean isComplete() {
    return complete;
  }

  synchronized int exitCode() {
    return exitCode;
  }

  synchronized String output() {
    StringBuilder text = new StringBuilder(head);
    if (!tail.isEmpty()) {
      if (droppedChars > 0) {
        text.append("\n[... ").append(droppedChars).append(" characters truncated ...]\n");
      }
      for (String line : tail) {
        text.append(line);
      }
    }
    text.append(pendingLine);
    return AnsiText.strip(text.toString());
  }

  synchronized boolean looksLikePrompt() {
    String probe = AnsiText.strip(pendingLine.toString()).toLowerCase(Locale.ROOT);
    if (probe.isBlank()) {
      return false;
    }
    for (Pattern pattern : PROMPT_PATTERNS) {
      if (pattern.matcher(probe).find()) {
        return true;
      }
    }
    return false;
  }
}
