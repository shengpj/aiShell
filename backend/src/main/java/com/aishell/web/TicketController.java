package com.aishell.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

  private final TicketService tickets;

  public TicketController(TicketService tickets) {
    this.tickets = tickets;
  }

  @PostMapping
  public TicketResponse issue(@RequestBody(required = false) TicketRequest request) {
    String purpose = request == null || request.purpose() == null || request.purpose().isBlank()
        ? "terminal"
        : request.purpose();
    TicketService.IssuedTicket issued = tickets.issue(purpose);
    return new TicketResponse(issued.ticket(), issued.expiresAt().toString());
  }

  public record TicketRequest(String purpose) {}

  public record TicketResponse(String ticket, String expiresAt) {}
}
