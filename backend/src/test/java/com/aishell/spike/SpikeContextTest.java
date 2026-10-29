package com.aishell.spike;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.springai.SpringAI;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "ai-shell.token=test-token")
class SpikeContextTest {

  @Autowired SpringAI springAI;
  @Autowired LlmAgent spikeAgent;
  @Value("${adk.spring-ai.model}")
  String configuredModel;

  @Test
  void wiresSpringAiBridgeAndAgent() {
    assertThat(springAI).isNotNull();
    assertThat(springAI.model()).isEqualTo(configuredModel);
    assertThat(spikeAgent.name()).isEqualTo("spike_agent");
    assertThat(spikeAgent.model().flatMap(com.google.adk.models.Model::model))
        .hasValueSatisfying(m -> assertThat(m).isSameAs(springAI));
  }
}
