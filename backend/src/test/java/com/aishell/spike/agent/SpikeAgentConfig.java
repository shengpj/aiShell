package com.aishell.spike.agent;

import com.aishell.model.ChatModelFactory;
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.springai.SpringAI;
import com.google.adk.tools.FunctionTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
public class SpikeAgentConfig {

  /** The spike plugs its own env-configured model in, since the app no longer autoconfigures one. */
  @Bean
  public SpringAI spikeSpringAI(
      @Value("${DEEPSEEK_BASE_URL:https://sub.vcnovb.cn}") String baseUrl,
      @Value("${DEEPSEEK_API_KEY:test-key}") String apiKey,
      @Value("${DEEPSEEK_MODEL:deepseek-v4.1-flash}") String model) {
    return new ChatModelFactory().create(baseUrl, model, apiKey);
  }

  @Bean
  public LlmAgent spikeAgent(SpringAI springAI) {
    return LlmAgent.builder()
        .name("spike_agent")
        .description("Verifies the ADK + Spring AI bridge against an OpenAI-compatible model.")
        .model(springAI)
        .instruction(
            """
            You are a verification agent.
            When the user asks for the server time, you MUST call the getServerTime tool.
            When the user refers to a previous tool call, answer from conversation history.
            Keep answers to one short sentence and include the tool result verbatim.
            """)
        .tools(FunctionTool.create(OpsTools.class, "getServerTime"))
        .build();
  }
}
