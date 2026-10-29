package com.aishell.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class AiCommandGuardTest {

  private static AiCommandGuard guard;

  @BeforeAll
  static void setUp() throws IOException {
    AiProperties properties = new AiProperties();
    properties.setDenyPatterns(denyPatternsFromApplicationYaml());
    guard = new AiCommandGuard(properties);
  }

  @Test
  void blocksDestructiveCommands() {
    assertBlocked("rm -rf /");
    assertBlocked("rm -fr / --no-preserve-root");
    assertBlocked("sudo rm -rf ~");
    assertBlocked("rm -rf *");
    assertBlocked("mkfs.ext4 /dev/sdb1");
    assertBlocked("dd if=/dev/zero of=/dev/sda bs=1M");
    assertBlocked("shutdown -h now");
    assertBlocked("sudo reboot");
    assertBlocked(":(){ :|:& };:");
    assertBlocked("chmod -R 777 /");
    assertBlocked("chown -R nobody /");
    assertBlocked("echo corrupted > /dev/sda");
    assertBlocked("iptables -F");
    assertBlocked("userdel root");
  }

  @Test
  void allowsOrdinaryCommands() {
    assertAllowed("ls -la /tmp");
    assertAllowed("df -h");
    assertAllowed("rm /tmp/old.log");
    assertAllowed("chmod 755 /tmp/app");
    assertAllowed("systemctl status nginx");
    assertAllowed("echo 'reboot is documented in the runbook'");
    assertAllowed("docker ps --format '{{.Names}}'");
  }

  @Test
  void rejectsEmptyMultilineAndOversizedCommands() {
    assertThat(guard.reject("")).isPresent();
    assertThat(guard.reject("   ")).isPresent();
    assertThat(guard.reject("ls\nrm -rf /")).isPresent();
    assertThat(guard.reject("ls\r\n")).isPresent();
    assertThat(guard.reject("echo " + "a".repeat(5_000))).isPresent();
  }

  private static void assertBlocked(String command) {
    assertThat(guard.reject(command)).as(command).isPresent();
  }

  private static void assertAllowed(String command) {
    assertThat(guard.reject(command)).as(command).isEmpty();
  }

  private static List<String> denyPatternsFromApplicationYaml() throws IOException {
    List<PropertySource<?>> sources =
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
    assertThat(sources).isNotEmpty();
    PropertySource<?> source = sources.get(0);
    List<String> patterns = new ArrayList<>();
    for (int i = 0; ; i++) {
      Object value = source.getProperty("ai-shell.ai.deny-patterns[" + i + "]");
      if (value == null) {
        break;
      }
      patterns.add(value.toString());
    }
    assertThat(patterns).as("deny-list must be configured in application.yml").isNotEmpty();
    return patterns;
  }
}
