package com.aishell;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.shell.InteractiveProcessShellFactory;
import org.apache.sshd.server.shell.ProcessShellFactory;

/**
 * Local SSH target for manual UI checks: user test, password secret. Prefers a POSIX shell so the
 * AI command markers ({@code printf ... $?}) work; set AI_SHELL_DEV_SHELL to override.
 */
public final class DevSshServer {

  private DevSshServer() {}

  public static void main(String[] args) throws Exception {
    int port = args.length > 0 ? Integer.parseInt(args[0]) : 2222;
    SshServer sshd = SshServer.setUpDefaultServer();
    sshd.setHost("127.0.0.1");
    sshd.setPort(port);
    sshd.setKeyPairProvider(
        new SimpleGeneratorHostKeyProvider(
            Path.of(System.getProperty("java.io.tmpdir"), "aishell-dev-hostkey.ser")));
    sshd.setPasswordAuthenticator(
        (username, password, session) -> "test".equals(username) && "secret".equals(password));
    String shell = findPosixShell();
    sshd.setShellFactory(
        shell == null
            ? new InteractiveProcessShellFactory()
            : new ProcessShellFactory(shell, "-i"));
    sshd.start();
    System.out.println(
        "dev ssh listening on 127.0.0.1:" + sshd.getPort() + " (test/secret, shell " + shell + ")");
    Thread.currentThread().join();
  }

  private static String findPosixShell() {
    String configured = System.getenv("AI_SHELL_DEV_SHELL");
    if (configured != null && !configured.isBlank()) {
      return configured;
    }
    for (String candidate :
        new String[] {
          "C:\\Program Files\\Git\\bin\\bash.exe",
          "C:\\Program Files\\Git\\usr\\bin\\bash.exe",
          "/bin/bash",
          "/usr/bin/bash"
        }) {
      if (Files.isRegularFile(Path.of(candidate))) {
        return candidate;
      }
    }
    return null;
  }
}
