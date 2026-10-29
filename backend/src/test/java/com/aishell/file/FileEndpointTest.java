package com.aishell.file;

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
class FileEndpointTest {

  private static final Path DATA_DIR = tempDir();

  private final HttpClient http = HttpClient.newHttpClient();

  @Value("${local.server.port}")
  int port;

  @Autowired TicketService tickets;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("ai-shell.data.dir", DATA_DIR::toString);
  }

  @Test
  void rejectsRequestsWithoutCredentials() throws Exception {
    assertThat(send("/api/files?session=missing&path=.", null).statusCode()).isEqualTo(401);
  }

  @Test
  void filesTicketWorksOnce() throws Exception {
    String ticket = tickets.issue("files").ticket();

    HttpResponse<String> first = send("/api/files?session=missing&path=.", ticket);
    assertThat(first.statusCode()).isEqualTo(409);
    assertThat(first.body()).contains("not connected");

    assertThat(send("/api/files?session=missing&path=.", ticket).statusCode()).isEqualTo(401);
  }

  @Test
  void rejectsTerminalTicketsForTheFilesPurpose() throws Exception {
    String ticket = tickets.issue("terminal").ticket();

    assertThat(send("/api/files?session=missing&path=.", ticket).statusCode()).isEqualTo(401);
  }

  private HttpResponse<String> send(String path, String ticket)
      throws IOException, InterruptedException {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    if (ticket != null) {
      builder.header("X-AI-Shell-Ticket", ticket);
    }
    return http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private static Path tempDir() {
    try {
      return Files.createTempDirectory("aishell-file-api");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
