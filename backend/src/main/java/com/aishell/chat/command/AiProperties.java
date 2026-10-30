package com.aishell.chat.command;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ai-shell.ai")
public class AiProperties {

  private int commandTimeoutSeconds = 120;
  private int turnTimeoutSeconds = 300;
  private int approvalTimeoutSeconds = 300;
  private AiExecutionPolicy executionPolicy = AiExecutionPolicy.AUTO;
  private List<String> denyPatterns = new ArrayList<>();

  public int getCommandTimeoutSeconds() {
    return commandTimeoutSeconds;
  }

  public void setCommandTimeoutSeconds(int commandTimeoutSeconds) {
    this.commandTimeoutSeconds = commandTimeoutSeconds;
  }

  public int getTurnTimeoutSeconds() {
    return turnTimeoutSeconds;
  }

  public void setTurnTimeoutSeconds(int turnTimeoutSeconds) {
    this.turnTimeoutSeconds = turnTimeoutSeconds;
  }

  public int getApprovalTimeoutSeconds() {
    return approvalTimeoutSeconds;
  }

  public void setApprovalTimeoutSeconds(int approvalTimeoutSeconds) {
    this.approvalTimeoutSeconds = approvalTimeoutSeconds;
  }

  public AiExecutionPolicy getExecutionPolicy() {
    return executionPolicy;
  }

  public void setExecutionPolicy(AiExecutionPolicy executionPolicy) {
    this.executionPolicy = executionPolicy;
  }

  public List<String> getDenyPatterns() {
    return denyPatterns;
  }

  public void setDenyPatterns(List<String> denyPatterns) {
    this.denyPatterns = denyPatterns;
  }
}
