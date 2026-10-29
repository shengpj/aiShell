package com.aishell.terminal;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class TerminalWebSocketConfig implements WebSocketConfigurer {

  private final TerminalHandler handler;
  private final TicketHandshakeInterceptor ticketInterceptor;
  private final String[] allowedOrigins;

  public TerminalWebSocketConfig(
      TerminalHandler handler,
      TicketHandshakeInterceptor ticketInterceptor,
      @Value("${ai-shell.cors.allowed-origins}") String allowedOrigins) {
    this.handler = handler;
    this.ticketInterceptor = ticketInterceptor;
    this.allowedOrigins =
        Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty())
            .toArray(String[]::new);
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry
        .addHandler(handler, "/ws/terminal")
        .addInterceptors(ticketInterceptor)
        .setAllowedOrigins(allowedOrigins);
  }
}
