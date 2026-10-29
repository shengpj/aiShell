package com.aishell.terminal;

import com.aishell.host.HostService.ConnectTarget;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UIKeyboardInteractive;
import com.jcraft.jsch.UserInfo;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Bridges one WebSocket connection to one SSH shell channel. Binary frames are raw PTY bytes in
 * both directions; text frames are JSON control messages. Host-key confirmation and password entry
 * happen inside the terminal stream itself.
 *
 * <p>AI commands are written into the same PTY under a session lock (ADR 0003): while one runs the
 * user's keystrokes are dropped except Esc (cancel) and, during a detected or manual takeover, the
 * input that answers a prompt.
 */
public class TerminalBridge {

  private static final Logger log = LoggerFactory.getLogger(TerminalBridge.class);
  private static final Duration PROMPT_TIMEOUT = Duration.ofMinutes(3);
  private static final long QUIET_PERIOD_MS = 400;
  private static final long QUIET_MAX_WAIT_MS = 3_000;
  private static final long MARKER_TIMEOUT_MS = 15_000;
  private static final long HOOK_INSTALL_TIMEOUT_MS = 4_000;
  private static final long AI_COMMAND_GRACE_MS = 5_000;
  private static final int HOOK_INPUT_LIMIT = 4_096;
  private static final byte[] CARRIAGE_RETURN = "\r\n".getBytes(StandardCharsets.US_ASCII);

  private final String id = UUID.randomUUID().toString();
  private final WebSocketSession ws;
  private final Path knownHosts;
  private final ObjectMapper json;
  private final UserCommandListener userCommands;
  private final ShellMarkerFilter hookFilter = new ShellMarkerFilter();

  private final Object sendLock = new Object();
  private final Object writeLock = new Object();
  private final Object promptLock = new Object();
  private final Object aiLock = new Object();
  private final Object hookLock = new Object();

  private final AtomicBoolean connecting = new AtomicBoolean();
  private volatile Pty pty;
  private volatile Session sshSession;
  private volatile int cols = 80;
  private volatile int rows = 24;
  private volatile boolean closed;
  /** Stable conversation key (host:port) so a reconnect resumes the same AI conversation. */
  private volatile String conversationKey;

  private ByteArrayOutputStream promptBuffer;
  private boolean promptEcho;
  private CompletableFuture<String> promptFuture;
  private volatile String password;
  private volatile String savedPassword;

  // AI command state, guarded by aiLock.
  private boolean aiRunning;
  private boolean aiTakeover;
  private boolean aiCancelled;
  private String aiCancelMessage;
  private String aiCommand;
  private CommandCapture aiCapture;
  private long aiStartedAt;
  private long aiPausedMs;
  private long aiPauseStartedAt;
  /** True while the next command marker belongs to an AI script and must not be audited. */
  private boolean aiSuppressUserCommand;
  private long aiSuppressDeadline;

  // Shell hook state, guarded by hookLock.
  private volatile boolean hookInstalling;
  private boolean hookReady;
  private String hookShell;
  private String hookWarning;
  private ByteArrayOutputStream hookInput;

  // Shared terminal state, guarded by aiLock.
  private boolean userLinePending;
  private boolean alternateScreen;
  private final byte[] scanTail = new byte[16];
  private volatile long lastPtyOutputAt;

  TerminalBridge(WebSocketSession ws, Path knownHosts, ObjectMapper json) {
    this(ws, knownHosts, json, null);
  }

  TerminalBridge(
      WebSocketSession ws, Path knownHosts, ObjectMapper json, UserCommandListener userCommands) {
    this.ws = ws;
    this.knownHosts = knownHosts;
    this.json = json;
    this.userCommands = userCommands;
  }

  String id() {
    return id;
  }

  /** The SSH session behind the shell; file browsing opens extra SFTP channels on it. */
  public Session sshSession() {
    return sshSession;
  }

  /** Host-derived conversation id; null until the bridge has been opened against a target. */
  public String conversationKey() {
    return conversationKey;
  }

  /** Starts relaying a PTY that was not created from SSH; used to swap the transport in tests. */
  void attach(Pty transport) {
    this.pty = transport;
    Thread.ofVirtual().name("pty-" + id).start(() -> relay(transport));
  }

  boolean isOpen() {
    Pty current = pty;
    return !closed && current != null && current.isOpen();
  }

  void open(ConnectTarget target, int cols, int rows) {
    if (closed) {
      return;
    }
    if (target == null
        || target.host() == null
        || target.host().isBlank()
        || target.user() == null
        || target.user().isBlank()) {
      sendError("host and user are required");
      return;
    }
    String keyFile = target.identity() == null ? "" : target.identity().trim();
    if (!keyFile.isEmpty() && !Files.isRegularFile(Path.of(keyFile))) {
      sendError("identity file not found: " + keyFile);
      return;
    }
    if (pty != null || !connecting.compareAndSet(false, true)) {
      sendError("terminal is already open");
      return;
    }
    conversationKey =
        target.host().trim().toLowerCase(Locale.ROOT) + ":" + target.port();
    if (cols > 0) {
      this.cols = cols;
    }
    if (rows > 0) {
      this.rows = rows;
    }
    Thread.ofVirtual()
        .name("ssh-" + target.user() + "@" + target.host())
        .start(() -> connect(target, keyFile));
  }

  private void connect(ConnectTarget target, String identity) {
    String host = target.host();
    String user = target.user();
    int port = target.port();
    String secret = target.secret() == null || target.secret().isEmpty() ? null : target.secret();
    boolean keyAuth = !identity.isEmpty();
    try {
      Files.createDirectories(knownHosts.toAbsolutePath().getParent());
      if (Files.notExists(knownHosts)) {
        Files.createFile(knownHosts);
      }
      JSch jsch = new JSch();
      jsch.setKnownHosts(knownHosts.toAbsolutePath().toString());
      if (keyAuth) {
        if (secret == null) {
          jsch.addIdentity(identity);
        } else {
          jsch.addIdentity(identity, secret.getBytes(StandardCharsets.UTF_8));
        }
      }

      savedPassword = keyAuth ? null : secret;
      Session session = jsch.getSession(user, host, port);
      session.setUserInfo(new TerminalUserInfo());
      session.setConfig("StrictHostKeyChecking", "ask");
      session.setServerAliveInterval(30_000);
      session.connect(20_000);
      sshSession = session;

      ChannelShell shell = (ChannelShell) session.openChannel("shell");
      shell.setPtyType("xterm-256color", cols, rows, 0, 0);
      shell.connect(20_000);
      shell.setEnv("TERM", "xterm-256color");
      shell.setEnv("COLORTERM", "truecolor");

      Pty created = new JschPty(session, shell);
      if (closed) {
        created.close();
        return;
      }
      pty = created;
      Map<String, Object> opened = new LinkedHashMap<>();
      opened.put("type", "opened");
      opened.put("session", id);
      sendJson(opened);
      installHook(probeShell(session));
      relay(created);
    } catch (Exception e) {
      String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      log.info("ssh connection to {}@{}:{} failed: {}", user, host, port, message);
      sendControl("error", message);
    } finally {
      savedPassword = null;
      connecting.set(false);
      close();
    }
  }

  private void relay(Pty current) {
    byte[] buffer = new byte[8192];
    try (InputStream in = current.input()) {
      int read;
      while (!closed && (read = in.read(buffer)) != -1) {
        byte[] chunk = Arrays.copyOf(buffer, read);
        byte[] visible = onPtyOutput(chunk);
        if (visible.length > 0) {
          sendBytes(visible);
        }
      }
      if (!closed) {
        int status = current.exitStatus();
        sendControl("closed", status >= 0 ? "exit " + status : "eof");
      }
    } catch (IOException e) {
      if (!closed) {
        sendControl("closed", "connection lost: " + e.getMessage());
      }
    }
  }

  private byte[] onPtyOutput(byte[] chunk) {
    lastPtyOutputAt = System.currentTimeMillis();
    List<ShellHook.Marker> markers = new ArrayList<>(2);
    byte[] visible = hookFilter.filter(chunk, markers::add);
    for (ShellHook.Marker marker : markers) {
      onHookMarker(marker);
    }
    synchronized (aiLock) {
      if (aiRunning) {
        aiSuppressDeadline = System.currentTimeMillis() + AI_COMMAND_GRACE_MS;
      }
      scanAlternateScreen(visible);
      if (!aiRunning || aiCapture == null) {
        return visible;
      }
      aiCapture.accept(visible);
      if (aiCapture.isComplete()) {
        aiSuppressDeadline = System.currentTimeMillis() + AI_COMMAND_GRACE_MS;
        aiLock.notifyAll();
        return visible;
      }
      if (!aiTakeover && aiCapture.looksLikePrompt()) {
        aiTakeover = true;
        aiPauseStartedAt = System.currentTimeMillis();
        sendAiState("takeover", "prompt");
        aiLock.notifyAll();
      }
    }
    return visible;
  }

  void write(byte[] data) {
    if (data.length == 0 || closed) {
      return;
    }
    synchronized (hookLock) {
      if (hookInstalling) {
        if (hookInput != null && hookInput.size() + data.length <= HOOK_INPUT_LIMIT) {
          hookInput.write(data, 0, data.length);
        }
        return;
      }
    }
    synchronized (promptLock) {
      if (promptFuture != null && !promptFuture.isDone()) {
        feedPrompt(data);
        return;
      }
    }
    synchronized (aiLock) {
      if (aiRunning) {
        if (aiTakeover) {
          try {
            writePty(data);
          } catch (IOException e) {
            cancelLocked("failed to write to ssh: " + e.getMessage());
          }
          if (containsAny(data, '\r', '\n')) {
            endTakeoverLocked();
          }
        } else if (containsAny(data, (char) 0x1b)) {
          cancelLocked("cancelled by user (Esc)");
        }
        return;
      }
      trackUserLine(data);
    }
    try {
      writePty(data);
    } catch (IOException e) {
      sendControl("error", "failed to write to ssh: " + e.getMessage());
      close();
    }
  }

  void resize(int cols, int rows) {
    if (cols <= 0 || rows <= 0) {
      return;
    }
    this.cols = cols;
    this.rows = rows;
    Pty current = pty;
    if (current != null) {
      try {
        current.resize(cols, rows);
      } catch (Exception e) {
        log.debug("resize failed: {}", e.getMessage());
      }
    }
  }

  void requestTakeover(boolean enabled) {
    synchronized (aiLock) {
      if (!aiRunning) {
        return;
      }
      if (enabled && !aiTakeover) {
        aiTakeover = true;
        aiPauseStartedAt = System.currentTimeMillis();
        sendAiState("takeover", "manual");
      } else if (!enabled && aiTakeover) {
        endTakeoverLocked();
      }
    }
  }

  public boolean cancelAiCommand() {
    synchronized (aiLock) {
      if (!aiRunning) {
        return false;
      }
      cancelLocked("stopped by user");
      return true;
    }
  }

  /**
   * Runs one command in the shared PTY and captures its output between random markers. Blocks the
   * caller until completion, timeout or cancellation; time spent in a user takeover does not count
   * against the timeout.
   */
  public AiCommandResult runAiCommand(String command, Duration timeout) {
    long start = System.nanoTime();
    long timeoutMs = timeout.toMillis();
    String token = UUID.randomUUID().toString().replace("-", "");
    CommandCapture capture = new CommandCapture(token);

    synchronized (aiLock) {
      String unavailable = readinessLocked();
      if (unavailable != null) {
        return AiCommandResult.unavailable(unavailable, elapsedMs(start));
      }
    }
    if (!awaitQuiet()) {
      return AiCommandResult.refused(
          "the terminal is still producing output; wait until the prompt returns",
          elapsedMs(start));
    }
    synchronized (aiLock) {
      String unavailable = readinessLocked();
      if (unavailable != null) {
        return AiCommandResult.unavailable(unavailable, elapsedMs(start));
      }
      aiRunning = true;
      aiTakeover = false;
      aiCancelled = false;
      aiCancelMessage = null;
      aiCommand = command;
      aiCapture = capture;
      aiStartedAt = System.currentTimeMillis();
      aiPausedMs = 0;
      aiSuppressUserCommand = true;
      aiSuppressDeadline = System.currentTimeMillis() + AI_COMMAND_GRACE_MS;
    }
    sendAiState("running", null);

    try {
      writePty(buildScript(command, capture).getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      failAiLocked();
      sendAiState("idle", null);
      return AiCommandResult.unavailable(
          "failed to write to the terminal: " + e.getMessage(), elapsedMs(start));
    }

    boolean timedOut = false;
    boolean markerTimeout = false;
    try {
      synchronized (aiLock) {
        while (true) {
          if (capture.isComplete() || aiCancelled) {
            break;
          }
          long now = System.currentTimeMillis();
          if (!aiTakeover) {
            long active = now - aiStartedAt - aiPausedMs;
            if (active >= timeoutMs) {
              timedOut = true;
              break;
            }
            if (!capture.hasStarted() && active >= MARKER_TIMEOUT_MS) {
              markerTimeout = true;
              break;
            }
            long slice = Math.min(timeoutMs - active, 200);
            aiLock.wait(Math.max(1, slice));
          } else {
            aiLock.wait(200);
          }
        }
        if (timedOut || markerTimeout) {
          interruptPty();
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      synchronized (aiLock) {
        cancelLocked("interrupted");
      }
    }

    AiCommandResult result;
    synchronized (aiLock) {
      String output = capture.output();
      if (aiCancelled) {
        result =
            new AiCommandResult(
                AiCommandResult.Status.CANCELLED,
                -1,
                output,
                aiCancelMessage,
                elapsedMs(start));
      } else if (markerTimeout) {
        aiSuppressUserCommand = false;
        result =
            AiCommandResult.unavailable(
                "the shell did not echo the command markers; AI commands need a POSIX shell",
                elapsedMs(start));
      } else if (timedOut) {
        result =
            new AiCommandResult(
                AiCommandResult.Status.TIMEOUT,
                -1,
                output,
                "command timed out after " + timeout.toSeconds() + "s and was interrupted",
                elapsedMs(start));
      } else {
        result = AiCommandResult.completed(capture.exitCode(), output, elapsedMs(start));
      }
      aiRunning = false;
      aiTakeover = false;
      aiCommand = null;
      aiCapture = null;
    }
    sendAiState("idle", null);
    return result;
  }

  private String readinessLocked() {
    Pty current = pty;
    if (closed || current == null || !current.isOpen()) {
      return "terminal is not connected";
    }
    if (aiRunning) {
      return "another AI command is already running";
    }
    if (hookInstalling) {
      return "the terminal is still installing command auditing; try again in a moment";
    }
    if (userLinePending) {
      return "the user has an unfinished command line; AI will not type into it";
    }
    if (alternateScreen) {
      return "a full-screen program is running; AI refuses to type into it";
    }
    return null;
  }

  private boolean awaitQuiet() {
    long deadline = System.currentTimeMillis() + QUIET_MAX_WAIT_MS;
    while (true) {
      long quietFor = System.currentTimeMillis() - lastPtyOutputAt;
      if (quietFor >= QUIET_PERIOD_MS) {
        return true;
      }
      if (System.currentTimeMillis() >= deadline) {
        return false;
      }
      try {
        Thread.sleep(Math.min(50, Math.max(1, QUIET_PERIOD_MS - quietFor)));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
  }

  private String buildScript(String command, CommandCapture capture) {
    return "printf '\\n"
        + capture.beginMarker()
        + "\\n'\n"
        + command
        + "\nprintf '\\n"
        + capture.endPrefix()
        + "%s>>\\n' \"$?\"\n";
  }

  /**
   * Detects the login shell through a throwaway exec channel. The interactive shell and the exec
   * channel are both started by sshd from the same login shell, so this is a good approximation.
   */
  private static String probeShell(Session session) {
    ChannelExec exec = null;
    try {
      exec = (ChannelExec) session.openChannel("exec");
      exec.setCommand("printf 'AISHELL-SHELL=%s\\n' \"$0\"");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      InputStream in = exec.getInputStream();
      exec.connect(5_000);
      long deadline = System.currentTimeMillis() + 3_000;
      byte[] buffer = new byte[128];
      while (System.currentTimeMillis() < deadline && out.size() < 256) {
        int available = in.available();
        if (available > 0) {
          int read = in.read(buffer, 0, Math.min(available, buffer.length));
          if (read < 0) {
            break;
          }
          out.write(buffer, 0, read);
          continue;
        }
        if (exec.isClosed()) {
          break;
        }
        Thread.sleep(20);
      }
      String text = out.toString(StandardCharsets.UTF_8);
      int idx = text.indexOf("AISHELL-SHELL=");
      if (idx < 0) {
        return null;
      }
      String value = text.substring(idx + "AISHELL-SHELL=".length());
      int newline = value.indexOf('\n');
      return (newline < 0 ? value : value.substring(0, newline)).trim();
    } catch (Exception e) {
      log.debug("shell probe failed: {}", e.getMessage());
      return null;
    } finally {
      if (exec != null) {
        exec.disconnect();
      }
    }
  }

  /**
   * Types the audit installer into the shell (ADR 0010). User keystrokes are buffered until the
   * hook reports it is live; unsupported shells keep working, just without user-command auditing.
   */
  void installHook(String probedShell) {
    ShellHook.Shell shell = ShellHook.shellOf(probedShell);
    if (shell == null) {
      sendHookState(
          "unavailable",
          probedShell,
          probedShell == null
              ? "could not detect the login shell"
              : "command auditing needs bash or zsh");
      return;
    }
    synchronized (hookLock) {
      hookInstalling = true;
      hookReady = false;
      hookShell = shell.id();
      hookWarning = null;
      hookInput = new ByteArrayOutputStream();
    }
    sendHookState("installing", shell.id(), null);
    try {
      writePty((ShellHook.INSTALL_LINE + "\r").getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      failHook("failed to install command auditing: " + e.getMessage());
      return;
    }
    Thread.ofVirtual().name("hook-" + id).start(this::watchHookInstall);
  }

  private void watchHookInstall() {
    try {
      Thread.sleep(HOOK_INSTALL_TIMEOUT_MS);
    } catch (InterruptedException e) {
      return;
    }
    synchronized (hookLock) {
      if (!hookInstalling) {
        return;
      }
    }
    interruptPty();
    failHook("the shell did not answer the audit hook");
  }

  private void failHook(String message) {
    ByteArrayOutputStream buffered;
    synchronized (hookLock) {
      if (!hookInstalling && !hookReady) {
        return;
      }
      hookInstalling = false;
      hookReady = false;
      hookWarning = null;
      buffered = hookInput;
      hookInput = null;
    }
    sendHookState("unavailable", hookShell, message);
    if (buffered != null && buffered.size() > 0) {
      write(buffered.toByteArray());
    }
  }

  private void onHookMarker(ShellHook.Marker marker) {
    switch (marker.kind()) {
      case "I" -> sendHookPayload();
      case "H" -> hookLive(marker.payload());
      case "D" -> auditUserCommand(marker.payload());
      case "W" -> hookWarningChanged(marker.payload());
      default -> log.debug("unknown shell hook marker: {}", marker.kind());
    }
  }

  /** The shell cannot capture everything it is asked to audit; surface that instead of dropping it. */
  private void hookWarningChanged(String warning) {
    String shell;
    synchronized (hookLock) {
      if (!hookReady) {
        return;
      }
      hookWarning = warning == null || warning.isBlank() ? null : warning.trim();
      shell = hookShell;
    }
    if (hookWarning == null) {
      sendHookState("ready", shell, null);
      return;
    }
    String message =
        switch (hookWarning) {
          case "history-off" ->
              "history recording is off; commands run while it stays off are not audited";
          case "ignorespace" ->
              "HISTCONTROL=ignorespace is set; commands typed with a leading space are not audited";
          default -> "the shell cannot record every command (" + hookWarning + ")";
        };
    sendHookState("degraded", shell, message);
  }

  private void sendHookPayload() {
    String payload;
    synchronized (hookLock) {
      if (!hookInstalling || hookShell == null) {
        return;
      }
      payload = ShellHook.payload(hookShell);
    }
    if (payload == null) {
      failHook("command auditing does not support this shell");
      return;
    }
    try {
      writePty((payload + "\r").getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      failHook("failed to install command auditing: " + e.getMessage());
    }
  }

  private void hookLive(String shell) {
    ByteArrayOutputStream buffered;
    String name;
    synchronized (hookLock) {
      if (hookReady) {
        return;
      }
      hookInstalling = false;
      hookReady = true;
      if (shell != null && !shell.isBlank()) {
        hookShell = shell.trim();
      }
      name = hookShell;
      buffered = hookInput;
      hookInput = null;
    }
    sendHookState("ready", name, null);
    if (buffered != null && buffered.size() > 0) {
      write(buffered.toByteArray());
    }
  }

  private void auditUserCommand(String payload) {
    String[] parts = payload.split(";", 3);
    if (parts.length < 3) {
      return;
    }
    int exitCode;
    try {
      exitCode = Integer.parseInt(parts[0].trim());
    } catch (NumberFormatException e) {
      return;
    }
    String cwd = decodeBase64(parts[1]);
    String command = decodeBase64(parts[2]);
    if (cwd == null || command == null || command.isBlank()) {
      return;
    }
    synchronized (aiLock) {
      long now = System.currentTimeMillis();
      if (aiRunning) {
        aiSuppressDeadline = now + AI_COMMAND_GRACE_MS;
        return;
      }
      if (aiSuppressUserCommand && now <= aiSuppressDeadline) {
        aiSuppressUserCommand = false;
        return;
      }
      aiSuppressUserCommand = false;
    }
    UserCommandListener listener = userCommands;
    if (listener != null) {
      listener.onUserCommand(id, command, exitCode, cwd);
    }
  }

  private static String decodeBase64(String value) {
    try {
      byte[] bytes = Base64.getDecoder().decode(value.trim());
      return new String(bytes, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private void sendHookState(String state, String shell, String message) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("type", "hook");
    payload.put("state", state);
    if (shell != null && !shell.isBlank()) {
      payload.put("shell", shell);
    }
    if (message != null) {
      payload.put("message", message);
    }
    sendJson(payload);
  }

  private void writePty(byte[] data) throws IOException {
    Pty current = pty;
    if (current == null || closed) {
      throw new IOException("terminal is not connected");
    }
    synchronized (writeLock) {
      current.write(data);
    }
  }

  private void interruptPty() {
    Pty current = pty;
    if (current == null) {
      return;
    }
    try {
      synchronized (writeLock) {
        current.interrupt();
      }
    } catch (IOException e) {
      log.debug("failed to interrupt command: {}", e.getMessage());
    }
  }

  private void trackUserLine(byte[] data) {
    if (containsAny(data, (char) 0x03) || containsAny(data, '\r', '\n')) {
      userLinePending = false;
      return;
    }
    if (hasPrintableInput(data)) {
      userLinePending = true;
    }
  }

  /** True when the chunk types real characters, ignoring escape sequences such as arrow keys. */
  private static boolean hasPrintableInput(byte[] data) {
    for (int i = 0; i < data.length; i++) {
      int b = data[i] & 0xFF;
      if (b == 0x1b) {
        if (i + 1 < data.length && data[i + 1] == '[') {
          i += 2;
          while (i < data.length && (data[i] & 0xFF) < 0x40) {
            i++;
          }
        } else if (i + 1 < data.length) {
          i++;
        }
        continue;
      }
      if (b >= 32 && b != 127) {
        return true;
      }
    }
    return false;
  }

  private void scanAlternateScreen(byte[] chunk) {
    int n = Math.min(chunk.length, scanTail.length);
    System.arraycopy(scanTail, n, scanTail, 0, scanTail.length - n);
    System.arraycopy(chunk, chunk.length - n, scanTail, scanTail.length - n, n);
    String window = new String(scanTail, StandardCharsets.ISO_8859_1);
    int on = Math.max(window.lastIndexOf("\u001B[?1049h"), window.lastIndexOf("\u001B[?1047h"));
    on = Math.max(on, window.lastIndexOf("\u001B[?47h"));
    int off = Math.max(window.lastIndexOf("\u001B[?1049l"), window.lastIndexOf("\u001B[?1047l"));
    off = Math.max(off, window.lastIndexOf("\u001B[?47l"));
    if (on > off) {
      alternateScreen = true;
    } else if (off > on) {
      alternateScreen = false;
    }
  }

  private void cancelLocked(String message) {
    if (!aiRunning) {
      return;
    }
    aiCancelled = true;
    aiCancelMessage = message;
    interruptPty();
    aiLock.notifyAll();
  }

  private void endTakeoverLocked() {
    if (!aiTakeover) {
      return;
    }
    aiPausedMs += System.currentTimeMillis() - aiPauseStartedAt;
    aiTakeover = false;
    sendAiState("running", null);
    aiLock.notifyAll();
  }

  private void failAiLocked() {
    synchronized (aiLock) {
      aiRunning = false;
      aiTakeover = false;
      aiCommand = null;
      aiCapture = null;
    }
  }

  private static boolean containsAny(byte[] data, char... chars) {
    for (byte b : data) {
      for (char c : chars) {
        if ((b & 0xFF) == c) {
          return true;
        }
      }
    }
    return false;
  }

  private static long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }

  void close() {
    closed = true;
    sshSession = null;
    synchronized (hookLock) {
      hookInstalling = false;
      hookReady = false;
      hookWarning = null;
      hookInput = null;
    }
    Pty current = pty;
    pty = null;
    if (current != null) {
      current.close();
    }
    synchronized (aiLock) {
      if (aiRunning) {
        aiCancelled = true;
        aiCancelMessage = "terminal closed";
        aiLock.notifyAll();
      }
    }
    synchronized (promptLock) {
      if (promptFuture != null) {
        promptFuture.cancel(true);
        promptFuture = null;
      }
      promptBuffer = null;
    }
  }

  void sendError(String message) {
    sendControl("error", message);
  }

  private void feedPrompt(byte[] data) {
    for (byte raw : data) {
      int b = raw & 0xFF;
      if (b == 3) {
        sendBytes("^C\r\n".getBytes(StandardCharsets.US_ASCII));
        CompletableFuture<String> future = promptFuture;
        clearPrompt();
        if (future != null) {
          future.completeExceptionally(new JSchException("cancelled by user"));
        }
        return;
      }
      if (b == '\r' || b == '\n') {
        sendBytes(CARRIAGE_RETURN);
        String value = promptBuffer.toString(StandardCharsets.UTF_8);
        CompletableFuture<String> future = promptFuture;
        clearPrompt();
        if (future != null) {
          future.complete(value);
        }
        return;
      }
      if (b == 127 || b == 8) {
        trimLastCodePoint();
        if (promptEcho) {
          sendBytes("\b \b".getBytes(StandardCharsets.US_ASCII));
        }
        continue;
      }
      if (b >= 32 || b == '\t') {
        promptBuffer.write(raw);
        if (promptEcho) {
          sendBytes(new byte[] {raw});
        }
      }
    }
  }

  private void trimLastCodePoint() {
    byte[] bytes = promptBuffer.toByteArray();
    if (bytes.length == 0) {
      return;
    }
    int end = bytes.length;
    while (end > 1 && (bytes[end - 1] & 0xC0) == 0x80) {
      end--;
    }
    promptBuffer.reset();
    promptBuffer.write(bytes, 0, end - 1);
  }

  private String promptLine(String message, boolean echo) {
    CompletableFuture<String> future = new CompletableFuture<>();
    synchronized (promptLock) {
      promptBuffer = new ByteArrayOutputStream();
      promptEcho = echo;
      promptFuture = future;
    }
    String text = message == null ? "" : message;
    if (!text.isEmpty() && !Character.isWhitespace(text.charAt(text.length() - 1))) {
      text = text + " ";
    }
    sendBytes(text.getBytes(StandardCharsets.UTF_8));
    try {
      return future.get(PROMPT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      clearPrompt();
      throw new PromptException("timed out waiting for terminal input");
    } catch (ExecutionException e) {
      throw new PromptException(e.getCause() != null ? e.getCause().getMessage() : "prompt failed");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PromptException("interrupted");
    } catch (java.util.concurrent.CancellationException e) {
      throw new PromptException("cancelled by user");
    }
  }

  private static final class PromptException extends RuntimeException {
    PromptException(String message) {
      super(message);
    }
  }

  private void clearPrompt() {
    synchronized (promptLock) {
      promptFuture = null;
      promptBuffer = null;
    }
  }

  private void sendBytes(byte[] data) {
    boolean failed = false;
    synchronized (sendLock) {
      if (!ws.isOpen()) {
        return;
      }
      try {
        ws.sendMessage(new BinaryMessage(data));
      } catch (IOException e) {
        failed = true;
      }
    }
    if (failed) {
      close();
    }
  }

  private void sendControl(String type, String message) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("type", type);
    if (message != null) {
      payload.put("message", message);
    }
    sendJson(payload);
  }

  private void sendAiState(String state, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("type", "ai");
    payload.put("state", state);
    synchronized (aiLock) {
      if (aiCommand != null) {
        payload.put("command", aiCommand);
      }
    }
    if (reason != null) {
      payload.put("reason", reason);
    }
    sendJson(payload);
  }

  private void sendJson(Map<String, Object> payload) {
    try {
      String text = json.writeValueAsString(payload);
      synchronized (sendLock) {
        if (ws.isOpen()) {
          ws.sendMessage(new TextMessage(text));
        }
      }
    } catch (Exception e) {
      log.debug("failed to send control message: {}", e.getMessage());
    }
  }

  private final class TerminalUserInfo implements UserInfo, UIKeyboardInteractive {

    @Override
    public String getPassphrase() {
      return password;
    }

    @Override
    public String getPassword() {
      return password;
    }

    @Override
    public boolean promptPassword(String message) {
      String saved = savedPassword;
      if (saved != null && !saved.isEmpty()) {
        password = saved;
        return true;
      }
      password = promptLine(message, false);
      return true;
    }

    @Override
    public boolean promptPassphrase(String message) {
      password = promptLine(message, false);
      return true;
    }

    @Override
    public boolean promptYesNo(String message) {
      return promptLine(message, true).trim().equalsIgnoreCase("yes");
    }

    @Override
    public void showMessage(String message) {
      sendBytes((message + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String[] promptKeyboardInteractive(
        String destination, String name, String instruction, String[] prompt, boolean[] echo) {
      if (instruction != null && !instruction.isBlank()) {
        sendBytes((instruction + "\r\n").getBytes(StandardCharsets.UTF_8));
      }
      List<String> answers = new ArrayList<>(prompt.length);
      boolean secretUsed = false;
      for (int i = 0; i < prompt.length; i++) {
        boolean visible = echo != null && echo.length > i && echo[i];
        String saved = savedPassword;
        if (!visible
            && !secretUsed
            && saved != null
            && !saved.isEmpty()
            && isSecretPrompt(prompt[i])) {
          answers.add(saved);
          secretUsed = true;
          continue;
        }
        answers.add(promptLine(prompt[i], visible));
      }
      return answers.toArray(String[]::new);
    }

    private static boolean isSecretPrompt(String prompt) {
      if (prompt == null) {
        return false;
      }
      String lower = prompt.toLowerCase(Locale.ROOT);
      return lower.contains("assword") || lower.contains("assphrase");
    }
  }
}
