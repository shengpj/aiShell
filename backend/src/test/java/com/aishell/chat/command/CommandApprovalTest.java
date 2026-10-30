package com.aishell.chat.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.aishell.chat.ChatChannel;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CommandApprovalTest {

  @Test
  void waitsForApprovalAndResolvesWithTheTokenShownToTheUser() throws Exception {
    CommandApproval approvals = new CommandApproval();
    ChatChannel channel = mock(ChatChannel.class);
    CompletableFuture<CommandApproval.Decision> result =
        CompletableFuture.supplyAsync(
            () -> approvals.await("session-1", "echo ok", channel, Duration.ofSeconds(2)));

    var payload = org.mockito.ArgumentCaptor.forClass(Object.class);
    verify(channel, timeout(1000)).send(eq("command"), payload.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> event = (Map<String, Object>) payload.getValue();
    String token = (String) event.get("approvalToken");

    assertThat(event).containsEntry("state", "pending").containsEntry("command", "echo ok");
    assertThat(approvals.resolve(token, true)).isTrue();
    assertThat(result.get(2, TimeUnit.SECONDS)).extracting(CommandApproval.Decision::approved).isEqualTo(true);
  }

  @Test
  void cancellationRejectsThePendingCommand() throws Exception {
    CommandApproval approvals = new CommandApproval();
    ChatChannel channel = mock(ChatChannel.class);
    CompletableFuture<CommandApproval.Decision> result =
        CompletableFuture.supplyAsync(
            () -> approvals.await("session-1", "echo ok", channel, Duration.ofSeconds(2)));

    verify(channel, timeout(1000)).send(eq("command"), org.mockito.ArgumentMatchers.any());
    approvals.cancelSession("session-1");

    CommandApproval.Decision decision = result.get(2, TimeUnit.SECONDS);
    assertThat(decision.approved()).isFalse();
    assertThat(decision.reason()).isEqualTo("stopped by user");
  }
}
