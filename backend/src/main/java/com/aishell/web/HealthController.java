package com.aishell.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {

  private final String model;

  public HealthController(@Value("${adk.spring-ai.model:unknown}") String model) {
    this.model = model;
  }

  @GetMapping("/health")
  public HealthResponse health() {
    return new HealthResponse("UP", model);
  }

  public record HealthResponse(String status, String model) {}
}
