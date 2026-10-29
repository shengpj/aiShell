package com.aishell.terminal;

import java.io.IOException;
import java.io.InputStream;

/** Minimal view of a remote PTY used by {@link TerminalBridge}. */
interface Pty {

  InputStream input() throws IOException;

  void write(byte[] data) throws IOException;

  default void interrupt() throws IOException {
    write(new byte[] {3});
  }

  void resize(int cols, int rows);

  int exitStatus();

  boolean isOpen();

  void close();
}
