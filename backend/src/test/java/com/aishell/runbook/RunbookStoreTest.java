package com.aishell.runbook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class RunbookStoreTest {
  @TempDir Path dir;

  @Test
  void persistsVersionsAndRejectsStaleUpdates() {
    RunbookStore store = new RunbookStore(new ObjectMapper(), dir.toString());
    RunbookStore.Runbook created = store.insert("检查", "检查服务", "自定义",
        List.of(new RunbookStore.Field("service", "服务", "nginx")), List.of("systemctl status {{service}}"), List.of("报告状态"));

    assertThat(store.list()).extracting(RunbookStore.Runbook::title).containsExactly("检查");
    RunbookStore.Runbook updated = store.update(created.id(), "检查 v2", "检查服务", "自定义", created.fields(), created.steps(), created.success(), 1).orElseThrow();
    assertThat(updated.version()).isEqualTo(2);
    assertThat(store.update(created.id(), "旧版本", "检查服务", "自定义", created.fields(), created.steps(), created.success(), 1)).isEmpty();
    assertThat(store.delete(created.id())).isTrue();
    assertThat(store.list()).isEmpty();
  }
}
