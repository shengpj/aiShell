package com.aishell.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TokenAuthFilter extends OncePerRequestFilter {

  private static final String BEARER_PREFIX = "Bearer ";
  private static final String TICKET_HEADER = "X-AI-Shell-Ticket";
  private static final byte[] UNAUTHORIZED_BODY =
      "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);

  private final byte[] expectedToken;
  private final TicketService tickets;

  public TokenAuthFilter(@Value("${ai-shell.token:}") String token, TicketService tickets) {
    if (token == null || token.isBlank()) {
      throw new IllegalStateException(
          "ai-shell.token is not configured; start the backend with AI_SHELL_TOKEN set");
    }
    this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
    this.tickets = tickets;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!request.getRequestURI().startsWith("/api/")
        || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
      chain.doFilter(request, response);
      return;
    }

    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header != null
        && header.startsWith(BEARER_PREFIX)
        && MessageDigest.isEqual(
            expectedToken, header.substring(BEARER_PREFIX.length()).getBytes(StandardCharsets.UTF_8))) {
      chain.doFilter(request, response);
      return;
    }

    String purpose = ticketPurpose(request.getRequestURI());
    if (purpose != null) {
      String ticket = request.getHeader(TICKET_HEADER);
      if (ticket != null && tickets.consume(ticket, purpose)) {
        chain.doFilter(request, response);
        return;
      }
    }

    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getOutputStream().write(UNAUTHORIZED_BODY);
  }

  private static String ticketPurpose(String uri) {
    if (uri.startsWith("/api/chat") || uri.equals("/api/agent/stop")) {
      return "chat";
    }
    if (uri.startsWith("/api/hosts") || uri.startsWith("/api/profiles")) {
      return "hosts";
    }
    if (uri.startsWith("/api/files")) {
      return "files";
    }
    return null;
  }
}
