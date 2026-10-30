package com.aishell.web;

import com.aishell.model.ModelService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {

  private final ModelService models;

  public HealthController(ModelService models) {
    this.models = models;
  }

  @GetMapping("/health")
  public HealthResponse health() {
    return new HealthResponse("UP", models.defaultName().orElse(""));
  }

  public record HealthResponse(String status, String model) {}
}
