package com.aishell.terminal;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.Session;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class JschPty implements Pty {

  private final Session session;
  private final ChannelShell channel;
  private final InputStream in;
  private final OutputStream out;

  JschPty(Session session, ChannelShell channel) throws IOException {
    this.session = session;
    this.channel = channel;
    this.in = channel.getInputStream();
    this.out = channel.getOutputStream();
  }

  @Override
  public InputStream input() {
    return in;
  }

  @Override
  public void write(byte[] data) throws IOException {
    out.write(data);
    out.flush();
  }

  @Override
  public void resize(int cols, int rows) {
    if (channel.isConnected()) {
      channel.setPtySize(cols, rows, 0, 0);
    }
  }

  @Override
  public int exitStatus() {
    return channel.getExitStatus();
  }

  @Override
  public boolean isOpen() {
    return channel.isConnected();
  }

  @Override
  public void close() {
    channel.disconnect();
    session.disconnect();
  }
}
