package com.aishell.host;

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
class HostEndpointTest {

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
    assertThat(send("GET", "/api/hosts", null, null, false).statusCode()).isEqualTo(401);
  }

  @Test
  void hostsTicketWorksOnce() throws Exception {
    String ticket = tickets.issue("hosts").ticket();
    String body = "{\"name\":\"ticket host\",\"address\":\"10.1.1.1\",\"port\":22}";

    HttpResponse<String> created = send("POST", "/api/hosts", body, ticket, false);
    assertThat(created.statusCode()).isEqualTo(201);

    assertThat(send("GET", "/api/hosts", null, ticket, false).statusCode()).isEqualTo(401);
  }

  @Test
  void createsProfilesWithoutLeakingTheSecret() throws Exception {
    String hostId = createHost("10.2.2.2");

    HttpResponse<String> created =
        send(
            "POST",
            "/api/hosts/" + hostId + "/profiles",
            "{\"name\":\"root\",\"username\":\"root\",\"authType\":\"password\","
                + "\"secret\":\"hunter2\"}",
            null);
    assertThat(created.statusCode()).isEqualTo(201);
    assertThat(created.body()).doesNotContain("hunter2").contains("\"hasSecret\":true");

    HttpResponse<String> listed = send("GET", "/api/hosts", null, null);
    assertThat(listed.body())
        .contains(hostId)
        .contains("\"authType\":\"password\"")
        .contains("\"hasSecret\":true")
        .doesNotContain("hunter2")
        .doesNotContain("secret");
  }

  @Test
  void updatesAndSoftDeletesAHost() throws Exception {
    String hostId = createHost("10.3.3.3");

    HttpResponse<String> updated =
        send(
            "PUT",
            "/api/hosts/" + hostId,
            "{\"name\":\"renamed\",\"address\":\"10.3.3.4\",\"port\":2222}",
            null);
    assertThat(updated.statusCode()).isEqualTo(200);
    assertThat(updated.body()).contains("renamed").contains("2222");

    assertThat(send("DELETE", "/api/hosts/" + hostId, null, null).statusCode()).isEqualTo(204);
    assertThat(send("GET", "/api/hosts", null, null).body()).doesNotContain(hostId);
    assertThat(send("DELETE", "/api/hosts/" + hostId, null, null).statusCode()).isEqualTo(404);
  }

  @Test
  void deletingProfilesRetiresAHostOnlyWithItsLastConnection() throws Exception {
    String hostId = createHost("10.5.5.5");
    String first = createProfile(hostId, "first");
    String second = createProfile(hostId, "second");

    assertThat(send("DELETE", "/api/profiles/" + first, null, null).statusCode()).isEqualTo(204);
    HttpResponse<String> afterFirst = send("GET", "/api/hosts", null, null);
    assertThat(afterFirst.body()).contains(hostId).contains(second).doesNotContain(first);

    assertThat(send("DELETE", "/api/profiles/" + second, null, null).statusCode()).isEqualTo(204);
    assertThat(send("GET", "/api/hosts", null, null).body()).doesNotContain(hostId);
    assertThat(send("DELETE", "/api/hosts/" + hostId, null, null).statusCode()).isEqualTo(404);
  }

  @Test
  void rejectsInvalidProfilesWithProblemDetails() throws Exception {
    String hostId = createHost("10.4.4.4");

    HttpResponse<String> response =
        send(
            "POST",
            "/api/hosts/" + hostId + "/profiles",
            "{\"name\":\"p\",\"username\":\"root\",\"authType\":\"private_key\"}",
            null);

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("privateKeyPath is required");
  }

  private String createProfile(String hostId, String name) throws Exception {
    HttpResponse<String> response =
        send(
            "POST",
            "/api/hosts/" + hostId + "/profiles",
            "{\"name\":\"" + name + "\",\"username\":\"root\",\"authType\":\"password\"}",
            null);
    assertThat(response.statusCode()).isEqualTo(201);
    return response.body().replaceAll("(?s).*\"id\":\"([^\"]+)\".*", "$1");
  }

  private String createHost(String address) throws Exception {
    HttpResponse<String> response =
        send(
            "POST",
            "/api/hosts",
            "{\"name\":\"host " + address + "\",\"address\":\"" + address + "\",\"port\":22}",
            null);
    assertThat(response.statusCode()).isEqualTo(201);
    return response.body().replaceAll("(?s).*\"id\":\"([^\"]+)\".*", "$1");
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
      return Files.createTempDirectory("aishell-host-api");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
