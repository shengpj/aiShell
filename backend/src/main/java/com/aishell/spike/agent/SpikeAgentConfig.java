package com.aishell.spike.agent;

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.springai.SpringAI;
import com.google.adk.tools.FunctionTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SpikeAgentConfig {

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
