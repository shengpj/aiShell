package com.aishell.model;

import com.aishell.security.SecretCipher;
import com.google.adk.models.springai.SpringAI;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Model profiles managed by the user: validation, API-key encryption and resolution of the model
 * that a chat turn should run on.
 */
@Service
public class ModelService implements ChatModels {

  public record View(
      String id, String name, String baseUrl, String model, boolean hasApiKey) {}

  public record Input(String name, String baseUrl, String model, String apiKey) {}

  private static final Logger log = LoggerFactory.getLogger(ModelService.class);
  private static final int MAX_NAME = 80;
  private static final int MAX_MODEL = 200;

  private record Cached(String updatedAt, SpringAI model) {}

  private final ModelStore store;
  private final SecretCipher cipher;
  private final ChatModelFactory factory;
  private final Map<String, Cached> cache = new ConcurrentHashMap<>();

  public ModelService(ModelStore store, SecretCipher cipher, ChatModelFactory factory) {
    this.store = store;
    this.cipher = cipher;
    this.factory = factory;
  }

  public List<View> list() {
    return store.list().stream().map(ModelService::toView).toList();
  }

  /** Name of the model used when a chat request does not name one. */
  public Optional<String> defaultName() {
    return store.first().map(ModelStore.Row::name);
  }

  public View create(Input input) {
    String name = requireName(input);
    String baseUrl = requireBaseUrl(input);
    String model = requireModel(input);
    String apiKey = requireApiKey(input);
    return toView(store.insert(name, baseUrl, model, encrypt(apiKey)));
  }

  public View update(String id, Input input) {
    String name = requireName(input);
    String baseUrl = requireBaseUrl(input);
    String model = requireModel(input);
    String apiKey = input == null || input.apiKey() == null || input.apiKey().isBlank()
        ? null
        : encrypt(input.apiKey().trim());
    ModelStore.Row row =
        store
            .update(id, name, baseUrl, model, apiKey)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "model not found"));
    cache.remove(id);
    return toView(row);
  }

  public void delete(String id) {
    if (!store.delete(id)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "model not found");
    }
    cache.remove(id);
  }

  @Override
  public SpringAI forTurn(String modelId) {
    ModelStore.Row row =
        modelId == null || modelId.isBlank()
            ? store
                .first()
                .orElseThrow(
                    () ->
                        new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "no model is configured; add one in model settings"))
            : store
                .find(modelId)
                .orElseThrow(
                    () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "model not found"));
    Cached cached = cache.get(row.id());
    if (cached != null && cached.updatedAt().equals(row.updatedAt())) {
      return cached.model();
    }
    SpringAI model = factory.create(row.baseUrl(), row.model(), decrypt(row.apiKey()));
    cache.put(row.id(), new Cached(row.updatedAt(), model));
    log.info("chat turn uses model profile '{}' ({})", row.name(), row.model());
    return model;
  }

  private String requireName(Input input) {
    String name = input == null ? null : trim(input.name());
    if (name == null || name.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
    }
    if (name.length() > MAX_NAME) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "name is longer than " + MAX_NAME + " characters");
    }
    return name;
  }

  private String requireBaseUrl(Input input) {
    String baseUrl = input == null ? null : trim(input.baseUrl());
    if (baseUrl == null || baseUrl.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "baseUrl is required");
    }
    String scheme = baseUrl.toLowerCase(Locale.ROOT);
    if (!scheme.startsWith("http://") && !scheme.startsWith("https://")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "baseUrl must start with http:// or https://");
    }
    try {
      new URI(baseUrl);
    } catch (URISyntaxException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "baseUrl is not a valid URL", e);
    }
    return baseUrl;
  }

  private String requireModel(Input input) {
    String model = input == null ? null : trim(input.model());
    if (model == null || model.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "model is required");
    }
    if (model.length() > MAX_MODEL) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "model is longer than " + MAX_MODEL + " characters");
    }
    return model;
  }

  private String requireApiKey(Input input) {
    String apiKey = input == null ? null : trim(input.apiKey());
    if (apiKey == null || apiKey.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "apiKey is required");
    }
    return apiKey;
  }

  private String encrypt(String apiKey) {
    if (!cipher.available()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "credential encryption is not configured");
    }
    return cipher.encrypt(apiKey);
  }

  private String decrypt(String stored) {
    if (!cipher.available()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "credential encryption is not configured");
    }
    try {
      return cipher.decrypt(stored);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "stored api key cannot be decrypted", e);
    }
  }

  private static View toView(ModelStore.Row row) {
    return new View(
        row.id(), row.name(), row.baseUrl(), row.model(), !row.apiKey().isBlank());
  }

  private static String trim(String value) {
    return value == null ? null : value.trim();
  }
}
