package com.aishell.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversationStoreTest {

  @TempDir Path dir;

  @Test
  void rotatesEpochsAndRemembersThemAcrossInstances() {
    ConversationStore store = new ConversationStore(dir.toString());

    assertThat(store.current("1.2.3.4:22")).as("epoch 1 keeps the historical key").isEqualTo("1.2.3.4:22");
    assertThat(store.next("1.2.3.4:22")).isEqualTo("1.2.3.4:22#2");
    assertThat(store.current("1.2.3.4:22")).isEqualTo("1.2.3.4:22#2");
    assertThat(store.next("1.2.3.4:22")).isEqualTo("1.2.3.4:22#3");

    ConversationStore reopened = new ConversationStore(dir.toString());
    assertThat(reopened.current("1.2.3.4:22")).isEqualTo("1.2.3.4:22#3");
    assertThat(reopened.current("5.6.7.8:22")).as("other hosts start over").isEqualTo("5.6.7.8:22");
  }
}
