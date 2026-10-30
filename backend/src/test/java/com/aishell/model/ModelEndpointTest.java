package com.aishell.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.aishell.web.TicketService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = "ai-shell.token=test-token")
class ModelEndpointTest {

  private static final String MASTER_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
  private static final Path DATA_DIR = tempDir();

  private final HttpClient http = HttpClient.newHttpClient();

  @Value("${local.server.port}")
  int port;

  @Autowired TicketService tickets;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("ai-shell.data.dir", DATA_DIR::toString);
    registry.add("ai-shell.master-key", () -> MASTER_KEY);
  }

  @Test
  void rejectsRequestsWithoutCredentials() throws Exception {
    assertThat(send("GET", "/api/models", null, null, false).statusCode()).isEqualTo(401);
  }

  @Test
  void modelsTicketIsRequiredAndSingleUse() throws Exception {
    String ticket = tickets.issue("models").ticket();

    assertThat(send("GET", "/api/models", null, ticket, false).statusCode()).isEqualTo(200);
    assertThat(send("GET", "/api/models", null, ticket, false).statusCode()).isEqualTo(401);
    String hostsTicket = tickets.issue("hosts").ticket();
    assertThat(send("GET", "/api/models", null, hostsTicket, false).statusCode()).isEqualTo(401);
  }

  @Test
  void createsModelsWithoutLeakingTheApiKey() throws Exception {
    HttpResponse<String> created = send("POST", "/api/models", model("first", "sk-secret"), null);

    assertThat(created.statusCode()).isEqualTo(201);
    assertThat(created.body())
        .contains("\"name\":\"first\"")
        .contains("\"hasApiKey\":true")
        .doesNotContain("sk-secret");

    assertThat(send("GET", "/api/models", null, null).body())
        .contains("\"name\":\"first\"")
        .doesNotContain("sk-secret")
        .doesNotContain("apiKey");
  }

  @Test
  void updatesKeepTheStoredKeyWhenItIsOmitted() throws Exception {
    String id = create("editable");

    HttpResponse<String> updated =
        send(
            "PUT",
            "/api/models/" + id,
            "{\"name\":\"renamed\",\"baseUrl\":\"https://next.example\",\"model\":\"next\","
                + "\"apiKey\":\"\"}",
            null);

    assertThat(updated.statusCode()).isEqualTo(200);
    assertThat(updated.body())
        .contains("\"name\":\"renamed\"")
        .contains("\"model\":\"next\"")
        .contains("\"hasApiKey\":true");
  }

  @Test
  void deletesModels() throws Exception {
    String id = create("temporary");

    assertThat(send("DELETE", "/api/models/" + id, null, null).statusCode()).isEqualTo(204);
    assertThat(send("GET", "/api/models", null, null).body()).doesNotContain(id);
    assertThat(send("DELETE", "/api/models/" + id, null, null).statusCode()).isEqualTo(404);
  }

  @Test
  void rejectsInvalidModelSettings() throws Exception {
    HttpResponse<String> response =
        send(
            "POST",
            "/api/models",
            "{\"name\":\"bad\",\"baseUrl\":\"api.example.com\",\"model\":\"m\",\"apiKey\":\"k\"}",
            null);

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("baseUrl must start with http:// or https://");
  }

  @Test
  void reportsAMissingModelOnUpdate() throws Exception {
    assertThat(
            send("PUT", "/api/models/missing", model("name", "sk-x"), null).statusCode())
        .isEqualTo(404);
  }

  private String create(String name) throws Exception {
    HttpResponse<String> response = send("POST", "/api/models", model(name, "sk-secret"), null);
    assertThat(response.statusCode()).isEqualTo(201);
    return response.body().replaceAll("(?s).*\"id\":\"([^\"]+)\".*", "$1");
  }

  private static String model(String name, String apiKey) {
    return "{\"name\":\"" + name + "\",\"baseUrl\":\"https://api.deepseek.com\","
        + "\"model\":\"deepseek-chat\",\"apiKey\":\"" + apiKey + "\"}";
  }

  private HttpResponse<String> send(String method, String path, String body, String ticket)
      throws IOException, InterruptedException {
    return send(method, path, body, ticket, true);
  }

  private HttpResponse<String> send(
      String method, String path, String body, String ticket, boolean bearer)
      throws IOException, InterruptedException {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json");
    if (bearer) {
      builder.header("Authorization", "Bearer test-token");
    }
    if (ticket != null) {
      builder.header("X-AI-Shell-Ticket", ticket);
    }
    if (body != null) {
      builder.method(method, HttpRequest.BodyPublishers.ofString(body));
    } else {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static Path tempDir() {
    try {
      return Files.createTempDirectory("aishell-model-api");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
