package com.aishell.model;

import com.google.adk.models.springai.SpringAI;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.stereotype.Component;

/**
 * Builds a Spring AI OpenAI model from stored settings. Any OpenAI-compatible gateway works because
 * the base URL is taken verbatim from the profile.
 */
@Component
public class ChatModelFactory {

  private static final Duration TIMEOUT = Duration.ofSeconds(120);
  private static final int MAX_RETRIES = 2;
  /** Fixed sampling temperature; it is not exposed as a per-profile setting. */
  private static final double TEMPERATURE = 0.2;

  public SpringAI create(String baseUrl, String modelName, String apiKey) {
    OpenAIClient client =
        OpenAiSetup.setupSyncClient(
            baseUrl,
            apiKey,
            null,
            null,
            null,
            null,
            false,
            false,
            modelName,
            TIMEOUT,
            MAX_RETRIES,
            null,
            Map.of(),
            ObservationRegistry.NOOP,
            Metrics.globalRegistry,
            List.of());
    // Streaming goes through the async client, so it has to be built from the same settings;
    // otherwise the builder tries to create one from its own (unset) credentials.
    OpenAIClientAsync asyncClient =
        OpenAiSetup.setupAsyncClient(
            baseUrl,
            apiKey,
            null,
            null,
            null,
            null,
            false,
            false,
            modelName,
            TIMEOUT,
            MAX_RETRIES,
            null,
            Map.of(),
            ObservationRegistry.NOOP,
            Metrics.globalRegistry,
            List.of());
    OpenAiChatOptions options =
        OpenAiChatOptions.builder().model(modelName).temperature(TEMPERATURE).build();
    OpenAiChatModel chatModel =
        OpenAiChatModel.builder()
            .openAiClient(client)
            .openAiClientAsync(asyncClient)
            .options(options)
            .toolCallingManager(ToolCallingManager.builder().build())
            .build();
    return new SpringAI(chatModel, modelName);
  }
}
