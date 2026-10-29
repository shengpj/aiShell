package com.aishell.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TicketServiceTest {

  private static final class MutableClock extends Clock {
    private Instant now = Instant.parse("2026-10-27T00:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }
  }

  private final MutableClock clock = new MutableClock();
  private final TicketService tickets = new TicketService(Duration.ofSeconds(30), clock);

  @Test
  void ticketIsSingleUse() {
    TicketService.IssuedTicket issued = tickets.issue("terminal");

    assertThat(tickets.consume(issued.ticket(), "terminal")).isTrue();
    assertThat(tickets.consume(issued.ticket(), "terminal")).isFalse();
  }

  @Test
  void ticketExpires() {
    TicketService.IssuedTicket issued = tickets.issue("terminal");
    clock.advance(Duration.ofSeconds(31));

    assertThat(tickets.consume(issued.ticket(), "terminal")).isFalse();
  }

  @Test
  void ticketIsBoundToPurpose() {
    TicketService.IssuedTicket issued = tickets.issue("terminal");

    assertThat(tickets.consume(issued.ticket(), "chat")).isFalse();
    assertThat(tickets.consume(issued.ticket(), "terminal")).isFalse();
  }

  @Test
  void blankTicketIsRejected() {
    assertThat(tickets.consume(null, "terminal")).isFalse();
    assertThat(tickets.consume("", "terminal")).isFalse();
    assertThat(tickets.consume("   ", "terminal")).isFalse();
  }
}
