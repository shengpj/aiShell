package com.aishell.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = "ai-shell.token=test-token")
class BackendAuthTest {

  private final HttpClient http = HttpClient.newHttpClient();

  @Value("${local.server.port}")
  int port;

  @Test
  void rejectsMissingToken() throws Exception {
    HttpResponse<String> response = get(null);

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.body()).contains("unauthorized");
  }

  @Test
  void rejectsWrongToken() throws Exception {
    HttpResponse<String> response = get("Bearer nope");

    assertThat(response.statusCode()).isEqualTo(401);
  }

  @Test
  void acceptsValidTokenAndReportsModel() throws Exception {
    HttpResponse<String> response = get("Bearer test-token");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"status\":\"UP\"").contains("\"model\":");
  }

  @Test
  void preflightIsAllowedWithoutToken() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/health"))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .header("Origin", "http://localhost:1420")
            .header("Access-Control-Request-Method", "GET")
            .build();

    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("access-control-allow-origin"))
        .contains("http://localhost:1420");
  }

  private HttpResponse<String> get(String authorization) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/health")).GET();
    if (authorization != null) {
      builder.header("Authorization", authorization);
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }
}
