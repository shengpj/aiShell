package com.aishell.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.events.Event;
import com.google.adk.sessions.GetSessionConfig;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteAgentSessionServiceTest {

  private static final String APP = "ai-shell";
  private static final String USER = "local";

  @TempDir Path dir;

  private SqliteAgentSessionService service() {
    return new SqliteAgentSessionService(dir.toString());
  }

  @Test
  void createsAndLoadsSessionWithState() {
    SqliteAgentSessionService service = service();
    Session created =
        service
            .createSession(APP, USER, new ConcurrentHashMap<>(Map.of("k", "v")), "host:22")
            .blockingGet();

    Session loaded =
        service.getSession(APP, USER, "host:22", Optional.empty()).blockingGet();

    assertThat(created.id()).isEqualTo("host:22");
    assertThat(loaded).isNotNull();
    assertThat(loaded.appName()).isEqualTo(APP);
    assertThat(loaded.userId()).isEqualTo(USER);
    assertThat(loaded.state()).containsEntry("k", "v");
    assertThat(loaded.events()).isEmpty();
  }

  @Test
  void eventsSurviveANewServiceInstance() {
    SqliteAgentSessionService service = service();
    service.createSession(APP, USER, new ConcurrentHashMap<>(), "host:22").blockingGet();
    Session session =
        service.getSession(APP, USER, "host:22", Optional.empty()).blockingGet();

    service.appendEvent(session, event("e1", "user", "check the disk", 1_000)).blockingGet();
    service.appendEvent(session, event("e2", "ops_assistant", "The disk is fine.", 1_001))
        .blockingGet();

    Session restored =
        service().getSession(APP, USER, "host:22", Optional.empty()).blockingGet();

    assertThat(restored.events()).hasSize(2);
    assertThat(restored.events().get(0).author()).isEqualTo("user");
    assertThat(restored.events().get(0).content().map(Content::text))
        .contains("check the disk");
    assertThat(restored.events().get(1).author()).isEqualTo("ops_assistant");
    assertThat(restored.events().get(1).timestamp()).isEqualTo(1_001);
  }

  @Test
  void listEventsAndSessionSummaryReflectStoredEvents() {
    SqliteAgentSessionService service = service();
    service.createSession(APP, USER, new ConcurrentHashMap<>(), "host:22").blockingGet();
    Session session =
        service.getSession(APP, USER, "host:22", Optional.empty()).blockingGet();
    service.appendEvent(session, event("e1", "user", "one", 1)).blockingGet();

    List<Event> events = service.listEvents(APP, USER, "host:22").blockingGet().events();
    List<Session> sessions = service.listSessions(APP, USER).blockingGet().sessions();

    assertThat(events).extracting(Event::id).containsExactly("e1");
    assertThat(sessions).extracting(Session::id).containsExactly("host:22");
    assertThat(sessions.get(0).events()).isEmpty();
  }

  @Test
  void recentEventConfigLimitsContext() {
    SqliteAgentSessionService service = service();
    service.createSession(APP, USER, new ConcurrentHashMap<>(), "host:22").blockingGet();
    Session session =
        service.getSession(APP, USER, "host:22", Optional.empty()).blockingGet();
    service.appendEvent(session, event("e1", "user", "one", 1_000)).blockingGet();
    service.appendEvent(session, event("e2", "ops_assistant", "two", 2_000)).blockingGet();

    Session trimmed =
        service
            .getSession(
                APP,
                USER,
                "host:22",
                Optional.of(GetSessionConfig.builder().numRecentEvents(1).build()))
            .blockingGet();

    assertThat(trimmed.events()).extracting(Event::id).containsExactly("e2");
  }

  @Test
  void deleteSessionRemovesEvents() {
    SqliteAgentSessionService service = service();
    service.createSession(APP, USER, new ConcurrentHashMap<>(), "host:22").blockingGet();
    Session session =
        service.getSession(APP, USER, "host:22", Optional.empty()).blockingGet();
    service.appendEvent(session, event("e1", "user", "one", 1)).blockingGet();

    service.deleteSession(APP, USER, "host:22").blockingAwait();

    assertThat(service.getSession(APP, USER, "host:22", Optional.empty()).blockingGet())
        .isNull();
    assertThat(service.listEvents(APP, USER, "host:22").blockingGet().events()).isEmpty();
  }

  @Test
  void deleteEventsBetweenRemovesOnlyTheRequestedRange() throws Exception {
    SqliteAgentSessionService service = service();
    service.createSession(APP, USER, new ConcurrentHashMap<>(), "host:22").blockingGet();
    Session session =
        service.getSession(APP, USER, "host:22", Optional.empty()).blockingGet();
    service.appendEvent(session, event("e1", "user", "one", 1)).blockingGet();
    Thread.sleep(5);
    Instant start = Instant.now();
    Thread.sleep(5);
    service.appendEvent(session, event("e2", "ops_assistant", "two", 2)).blockingGet();

    assertThat(service.deleteEventsBetween("host:22", start, null)).isEqualTo(1);

    assertThat(service.listEvents(APP, USER, "host:22").blockingGet().events())
        .extracting(Event::id)
        .containsExactly("e1");
  }

  @Test
  void deleteEventsBetweenStopsAtTheEndInstant() throws Exception {
    SqliteAgentSessionService service = service();
    service.createSession(APP, USER, new ConcurrentHashMap<>(), "host:22").blockingGet();
    Session session =
        service.getSession(APP, USER, "host:22", Optional.empty()).blockingGet();
    service.appendEvent(session, event("e1", "user", "one", 1)).blockingGet();
    Thread.sleep(5);
    Instant start = Instant.now();
    Thread.sleep(5);
    service.appendEvent(session, event("e2", "ops_assistant", "two", 2)).blockingGet();
    Thread.sleep(5);
    Instant end = Instant.now();
    Thread.sleep(5);
    service.appendEvent(session, event("e3", "user", "three", 3)).blockingGet();

    assertThat(service.deleteEventsBetween("host:22", start, end)).isEqualTo(1);

    assertThat(service.listEvents(APP, USER, "host:22").blockingGet().events())
        .extracting(Event::id)
        .containsExactly("e1", "e3");
  }

  @Test
  void deleteEventsBetweenIgnoresOtherSessions() throws Exception {
    SqliteAgentSessionService service = service();
    service.createSession(APP, USER, new ConcurrentHashMap<>(), "a:22").blockingGet();
    service.createSession(APP, USER, new ConcurrentHashMap<>(), "b:22").blockingGet();
    Session a = service.getSession(APP, USER, "a:22", Optional.empty()).blockingGet();
    Session b = service.getSession(APP, USER, "b:22", Optional.empty()).blockingGet();
    service.appendEvent(a, event("e1", "user", "one", 1)).blockingGet();
    service.appendEvent(b, event("e2", "user", "two", 2)).blockingGet();

    assertThat(service.deleteEventsBetween("a:22", Instant.EPOCH, null)).isEqualTo(1);

    assertThat(service.listEvents(APP, USER, "a:22").blockingGet().events()).isEmpty();
    assertThat(service.listEvents(APP, USER, "b:22").blockingGet().events())
        .extracting(Event::id)
        .containsExactly("e2");
  }

  private static Event event(String id, String author, String text, long timestamp) {
    return Event.builder()
        .id(id)
        .author(author)
        .content(Content.fromParts(Part.fromText(text)))
        .timestamp(timestamp)
        .build();
  }
}
