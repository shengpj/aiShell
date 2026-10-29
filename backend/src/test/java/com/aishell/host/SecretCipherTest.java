package com.aishell.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

  private static final String KEY =
      Base64.getEncoder().encodeToString(bytes(32, 7));

  @Test
  void roundTripsPlaintext() {
    SecretCipher cipher = new SecretCipher(KEY);

    String stored = cipher.encrypt("hunter2");

    assertThat(stored).startsWith("v1:").doesNotContain("hunter2");
    assertThat(cipher.decrypt(stored)).isEqualTo("hunter2");
  }

  @Test
  void usesAFreshIvPerEncryption() {
    SecretCipher cipher = new SecretCipher(KEY);

    assertThat(cipher.encrypt("same secret")).isNotEqualTo(cipher.encrypt("same secret"));
  }

  @Test
  void rejectsTamperedCiphertext() {
    SecretCipher cipher = new SecretCipher(KEY);
    String stored = cipher.encrypt("hunter2");
    int index = stored.length() / 2;
    char replacement = stored.charAt(index) == 'A' ? 'B' : 'A';
    StringBuilder tampered = new StringBuilder(stored);
    tampered.setCharAt(index, replacement);

    assertThatThrownBy(() -> cipher.decrypt(tampered.toString()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot be decrypted");
  }

  @Test
  void rejectsAKeyOfTheWrongLength() {
    String shortKey = Base64.getEncoder().encodeToString(bytes(16, 1));

    assertThatThrownBy(() -> new SecretCipher(shortKey))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("32 bytes");
  }

  @Test
  void reportsWhenNoKeyIsConfigured() {
    SecretCipher cipher = new SecretCipher("");

    assertThat(cipher.available()).isFalse();
    assertThatThrownBy(() -> cipher.encrypt("x"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not configured");
  }

  private static byte[] bytes(int length, int seed) {
    byte[] value = new byte[length];
    for (int i = 0; i < length; i++) {
      value[i] = (byte) (seed + i);
    }
    return value;
  }
}
