package com.aishell.terminal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Strips {@link ShellHook} markers from the PTY byte stream before it reaches the webview or the AI
 * capture. Chunk-safe: a marker (or a partial prefix) split across reads is held until it is
 * complete; everything else, including unrelated escape sequences, is passed through untouched.
 */
final class ShellMarkerFilter {

  static final byte[] PREFIX = ShellHook.MARKER_PREFIX_BYTES;
  private static final byte[] EMPTY = new byte[0];
  private static final int MAX_PENDING = 8 * 1024;
  private static final byte BEL = 0x07;
  private static final byte ESC = 0x1b;

  private byte[] pending = EMPTY;

  interface MarkerSink {
    void accept(ShellHook.Marker marker);
  }

  /** Returns the bytes that should be shown; complete markers are reported to {@code sink}. */
  byte[] filter(byte[] chunk, MarkerSink sink) {
    byte[] data;
    if (pending.length == 0) {
      data = chunk;
    } else {
      data = new byte[pending.length + chunk.length];
      System.arraycopy(pending, 0, data, 0, pending.length);
      System.arraycopy(chunk, 0, data, pending.length, chunk.length);
      pending = EMPTY;
    }

    ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
    int i = 0;
    while (i < data.length) {
      int start = indexOf(data, PREFIX, i);
      if (start < 0) {
        int keep = partialSuffix(data, i);
        out.write(data, i, data.length - i - keep);
        pending = Arrays.copyOfRange(data, data.length - keep, data.length);
        return out.toByteArray();
      }
      out.write(data, i, start - i);
      int bodyStart = start + PREFIX.length;
      int end = terminator(data, bodyStart);
      if (end < 0) {
        pending = Arrays.copyOfRange(data, start, data.length);
        if (pending.length > MAX_PENDING) {
          out.write(pending, 0, pending.length);
          pending = EMPTY;
        }
        return out.toByteArray();
      }
      String body =
          new String(data, bodyStart, end - bodyStart, StandardCharsets.ISO_8859_1);
      int semi = body.indexOf(';');
      String kind = semi < 0 ? body : body.substring(0, semi);
      String payload = semi < 0 ? "" : body.substring(semi + 1);
      if (!kind.isEmpty()) {
        sink.accept(new ShellHook.Marker(kind, payload));
      }
      i = data[end] == BEL ? end + 1 : end + 2;
    }
    return out.toByteArray();
  }

  private static int indexOf(byte[] data, byte[] needle, int from) {
    int last = data.length - needle.length;
    outer:
    for (int i = from; i <= last; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (data[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }

  /** Length of the longest suffix of {@code data[from..]} that starts a marker prefix. */
  private static int partialSuffix(byte[] data, int from) {
    int max = Math.min(PREFIX.length - 1, data.length - from);
    for (int len = max; len > 0; len--) {
      boolean match = true;
      for (int j = 0; j < len; j++) {
        if (data[data.length - len + j] != PREFIX[j]) {
          match = false;
          break;
        }
      }
      if (match) {
        return len;
      }
    }
    return 0;
  }

  /** Index of BEL, or of the ESC of an ST ({@code ESC \\}) terminator; -1 when incomplete. */
  private static int terminator(byte[] data, int from) {
    for (int i = from; i < data.length; i++) {
      if (data[i] == BEL) {
        return i;
      }
      if (data[i] == ESC) {
        if (i + 1 >= data.length) {
          return -1;
        }
        if (data[i + 1] == '\\') {
          return i;
        }
      }
    }
    return -1;
  }
}
