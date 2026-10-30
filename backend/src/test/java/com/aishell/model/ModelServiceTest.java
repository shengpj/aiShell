package com.aishell.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aishell.security.SecretCipher;
import com.google.adk.models.springai.SpringAI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

class ModelServiceTest {

  private static final String MASTER_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

  @TempDir Path dataDir;

  private ModelStore store;
  private RecordingFactory factory;
  private ModelService models;

  @BeforeEach
  void setUp() {
    store = new ModelStore(dataDir.toString());
    factory = new RecordingFactory();
    models = new ModelService(store, new SecretCipher(MASTER_KEY), factory);
  }

  @Test
  void storesTheApiKeyEncryptedAndNeverEchoesIt() {
    ModelService.View view = models.create(input("deepseek", "secret-key"));

    assertThat(view.hasApiKey()).isTrue();
    String stored = store.list().get(0).apiKey();
    assertThat(stored).startsWith("v1:").doesNotContain("secret-key");
    assertThat(new SecretCipher(MASTER_KEY).decrypt(stored)).isEqualTo("secret-key");
  }

  @Test
  void rejectsInvalidInput() {
    assertBadRequest(new ModelService.Input(" ", "https://x", "m", "k"));
    assertBadRequest(new ModelService.Input("n", "ftp://x", "m", "k"));
    assertBadRequest(new ModelService.Input("n", "https://x", "", "k"));
    assertBadRequest(new ModelService.Input("n", "https://x", "m", ""));
  }

  @Test
  void updatingWithoutAnApiKeyKeepsTheStoredOne() {
    ModelService.View created = models.create(input("deepseek", "secret-key"));

    ModelService.View updated =
        models.update(
        created.id(),
        new ModelService.Input("renamed", "https://other.example", "other-model", null));

    assertThat(updated.hasApiKey()).isTrue();
    models.forTurn(created.id());
    assertThat(factory.calls.get(0).apiKey()).isEqualTo("secret-key");
  }

  @Test
  void updatingWithAnApiKeyReplacesIt() {
    ModelService.View created = models.create(input("deepseek", "old-key"));

    models.update(created.id(), new ModelService.Input("n", "https://x", "m", "new-key"));

    models.forTurn(created.id());
    assertThat(factory.calls.get(0).apiKey()).isEqualTo("new-key");
  }

  @Test
  void theDefaultTurnUsesTheFirstCreatedModel() {
    models.create(input("first", "k1"));
    models.create(input("second", "k2"));

    models.forTurn(null);
    models.forTurn("");

    assertThat(factory.calls).hasSize(1);
    assertThat(factory.calls.get(0).model()).isEqualTo("deepseek-chat");
    assertThat(models.defaultName()).contains("first");
  }

  @Test
  void reusesTheCachedModelUntilTheProfileChanges() {
    ModelService.View created = models.create(input("deepseek", "k"));

    SpringAI first = models.forTurn(created.id());
    assertThat(models.forTurn(created.id())).isSameAs(first);
    assertThat(factory.calls).hasSize(1);

    models.update(
        created.id(), new ModelService.Input("n", "https://next.example", "next-model", null));

    models.forTurn(created.id());
    assertThat(factory.calls).hasSize(2);
    assertThat(factory.calls.get(1).baseUrl()).isEqualTo("https://next.example");
    assertThat(factory.calls.get(1).model()).isEqualTo("next-model");
  }

  @Test
  void deletingRemovesTheProfileAndItsCache() {
    ModelService.View created = models.create(input("deepseek", "k"));
    models.forTurn(created.id());

    models.delete(created.id());

    assertThat(models.list()).isEmpty();
    assertStatus(HttpStatus.NOT_FOUND, () -> models.forTurn(created.id()));
    assertStatus(HttpStatus.NOT_FOUND, () -> models.delete(created.id()));
  }

  @Test
  void unknownModelAndEmptyCatalogAreReportedPrecisely() {
    assertStatus(HttpStatus.CONFLICT, () -> models.forTurn(null));

    ModelService.View created = models.create(input("deepseek", "k"));
    assertStatus(HttpStatus.NOT_FOUND, () -> models.forTurn("missing"));
    assertThat(models.forTurn(created.id())).isNotNull();
  }

  @Test
  void withoutAMasterKeyNothingIsStoredOrRead() {
    ModelService unconfigured = new ModelService(store, new SecretCipher(""), factory);

    assertStatus(HttpStatus.SERVICE_UNAVAILABLE, () -> unconfigured.create(input("n", "k")));
    assertThat(models.create(input("deepseek", "k"))).isNotNull();
    assertStatus(HttpStatus.SERVICE_UNAVAILABLE, () -> unconfigured.forTurn(null));
  }

  @Test
  void unreadableStoredKeysAreReportedAsAConflict() {
    ModelStore.Row row = store.insert("broken", "https://x", "m", "not-ciphertext");

    assertStatus(HttpStatus.CONFLICT, () -> models.forTurn(row.id()));
  }

  private static ModelService.Input input(String name, String apiKey) {
    return new ModelService.Input(name, "https://api.deepseek.com", "deepseek-chat", apiKey);
  }

  private void assertBadRequest(ModelService.Input input) {
    assertStatus(HttpStatus.BAD_REQUEST, () -> models.create(input));
  }

  private static void assertStatus(HttpStatus expected, Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode()).isEqualTo(expected));
  }

  /** Records what the service asks to build without touching the network. */
  static final class RecordingFactory extends ChatModelFactory {

    record Call(String baseUrl, String model, String apiKey) {}

    final List<Call> calls = new ArrayList<>();

    @Override
    public SpringAI create(String baseUrl, String modelName, String apiKey) {
      calls.add(new Call(baseUrl, modelName, apiKey));
      return new SpringAI(new UnusedModel(), modelName);
    }
  }

  static final class UnusedModel implements ChatModel, StreamingChatModel {

    @Override
    public ChatResponse call(Prompt prompt) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
      throw new UnsupportedOperationException();
    }
  }
}
