package com.aishell.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** In-memory PTY: records what the bridge writes and lets tests push output back. */
final class FakePty implements Pty {

  private final PipedOutputStream inbound = new PipedOutputStream();
  private final PipedInputStream outbound = new PipedInputStream(inbound, 1 << 16);
  private final BlockingQueue<byte[]> writes = new LinkedBlockingQueue<>();
  private final List<byte[]> history = Collections.synchronizedList(new ArrayList<>());
  private volatile boolean open = true;

  FakePty() throws IOException {}

  @Override
  public InputStream input() {
    return outbound;
  }

  @Override
  public void write(byte[] data) {
    byte[] copy = data.clone();
    history.add(copy);
    writes.add(copy);
  }

  @Override
  public void interrupt() {
    write(new byte[] {3});
  }

  @Override
  public void resize(int cols, int rows) {}

  @Override
  public int exitStatus() {
    return 0;
  }

  @Override
  public boolean isOpen() {
    return open;
  }

  @Override
  public void close() {
    open = false;
    try {
      inbound.close();
    } catch (IOException ignored) {
      // pipe already closed
    }
  }

  void emit(String text) {
    try {
      inbound.write(text.getBytes(StandardCharsets.UTF_8));
      inbound.flush();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  byte[] pollWrite(Duration timeout) throws InterruptedException {
    return writes.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  List<byte[]> history() {
    synchronized (history) {
      return List.copyOf(history);
    }
  }

  boolean received(int value) {
    for (byte[] chunk : history()) {
      for (byte b : chunk) {
        if ((b & 0xFF) == value) {
          return true;
        }
      }
    }
    return false;
  }
}
