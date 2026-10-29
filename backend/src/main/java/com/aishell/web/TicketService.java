package com.aishell.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TicketService {

  private static final int PURGE_EVERY = 32;

  private final Duration ttl;
  private final Clock clock;
  private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
  private int issued;

  @Autowired
  public TicketService(@Value("${ai-shell.ticket.ttl-seconds:30}") long ttlSeconds) {
    this(Duration.ofSeconds(ttlSeconds), Clock.systemUTC());
  }

  TicketService(Duration ttl, Clock clock) {
    this.ttl = ttl;
    this.clock = clock;
  }

  public IssuedTicket issue(String purpose) {
    if (++issued % PURGE_EVERY == 0) {
      purgeExpired();
    }
    Instant expiresAt = clock.instant().plus(ttl);
    IssuedTicket issuedTicket = new IssuedTicket(UUID.randomUUID().toString(), expiresAt);
    tickets.put(issuedTicket.ticket(), new Ticket(purpose, expiresAt));
    return issuedTicket;
  }

  public boolean consume(String ticketId, String purpose) {
    if (ticketId == null || ticketId.isBlank()) {
      return false;
    }
    Ticket ticket = tickets.remove(ticketId);
    return ticket != null && ticket.purpose().equals(purpose) && ticket.expiresAt().isAfter(clock.instant());
  }

  private void purgeExpired() {
    Instant now = clock.instant();
    tickets.values().removeIf(t -> !t.expiresAt().isAfter(now));
  }

  record Ticket(String purpose, Instant expiresAt) {}

  public record IssuedTicket(String ticket, Instant expiresAt) {}
}
