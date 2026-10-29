package com.aishell.chat;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/** Serializes SSE events from the agent run and the command tool onto one emitter. */
public final class ChatChannel {

  /** Mirrors every outgoing event into the persisted transcript. */
  interface Sink {
    void onEvent(String event, Object data);
  }

  private final SseEmitter emitter;
  private final ObjectMapper json;
  private final Runnable onBroken;
  private final Sink sink;
  private final AtomicBoolean broken = new AtomicBoolean();

  ChatChannel(SseEmitter emitter, ObjectMapper json, Runnable onBroken, Sink sink) {
    this.emitter = emitter;
    this.json = json;
    this.onBroken = onBroken;
    this.sink = sink;
  }

  public void send(String event, Object data) {
    if (sink != null) {
      sink.onEvent(event, data);
    }
    if (broken.get()) {
      return;
    }
    String payload;
    try {
      payload = json.writeValueAsString(data);
    } catch (RuntimeException e) {
      return;
    }
    synchronized (this) {
      try {
        emitter.send(SseEmitter.event().name(event).data(payload, MediaType.APPLICATION_JSON));
      } catch (IOException | IllegalStateException e) {
        if (broken.compareAndSet(false, true)) {
          onBroken.run();
        }
      }
    }
  }

  boolean isBroken() {
    return broken.get();
  }

  void complete() {
    if (broken.get()) {
      return;
    }
    try {
      emitter.complete();
    } catch (IllegalStateException ignored) {
      // already completed by the container
    }
  }
}
