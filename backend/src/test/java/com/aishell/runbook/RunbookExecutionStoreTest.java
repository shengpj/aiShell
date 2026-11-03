package com.aishell.runbook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunbookExecutionStoreTest {
  @TempDir Path dir;

  @Test
  void recordsLifecycleAndListsNewestFirst() {
    RunbookExecutionStore store = new RunbookExecutionStore(dir.toString());
    String first = store.start("rb", 2, "session-1", "turn-1");
    store.finish(first, "completed", null);
    String second = store.start("rb", 3, "session-1", "turn-2");
    store.finish(second, "cancelled", "stopped by user");

    assertThat(store.list("rb", 10)).hasSize(2);
    assertThat(store.list("rb", 10).get(0).status()).isEqualTo("cancelled");
    assertThat(store.list("rb", 10).get(1).status()).isEqualTo("completed");
    assertThat(store.list("other", 10)).isEmpty();
  }
}
