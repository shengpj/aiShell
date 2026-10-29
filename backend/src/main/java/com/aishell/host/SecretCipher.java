package com.aishell.host;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM protection for stored profile secrets. The master key is generated and kept by the
 * Rust side in the Windows Credential Manager and handed to this process as base64 in
 * AI_SHELL_MASTER_KEY; without it the backend still serves host profiles but refuses to store or
 * read secrets.
 */
@Component
public class SecretCipher {

  private static final String PREFIX = "v1:";
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  public SecretCipher(@Value("${ai-shell.master-key:}") String encodedKey) {
    this.key = parseKey(encodedKey);
  }

  public boolean available() {
    return key != null;
  }

  public String encrypt(String plaintext) {
    SecretKeySpec current = requireKey();
    byte[] iv = new byte[IV_BYTES];
    random.nextBytes(iv);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, current, new GCMParameterSpec(TAG_BITS, iv));
      byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      byte[] payload = new byte[iv.length + sealed.length];
      System.arraycopy(iv, 0, payload, 0, iv.length);
      System.arraycopy(sealed, 0, payload, iv.length, sealed.length);
      return PREFIX + Base64.getEncoder().encodeToString(payload);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("failed to encrypt credential", e);
    }
  }

  public String decrypt(String stored) {
    SecretKeySpec current = requireKey();
    if (stored == null || !stored.startsWith(PREFIX)) {
      throw new IllegalStateException("stored credential has an unknown format");
    }
    byte[] payload = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
    if (payload.length <= IV_BYTES) {
      throw new IllegalStateException("stored credential is truncated");
    }
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          current,
          new GCMParameterSpec(TAG_BITS, Arrays.copyOf(payload, IV_BYTES)));
      byte[] plain =
          cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES);
      return new String(plain, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("stored credential cannot be decrypted", e);
    }
  }

  private SecretKeySpec requireKey() {
    if (key == null) {
      throw new IllegalStateException("credential encryption is not configured");
    }
    return key;
  }

  private static SecretKeySpec parseKey(String encoded) {
    if (encoded == null || encoded.isBlank()) {
      return null;
    }
    byte[] raw;
    try {
      raw = Base64.getDecoder().decode(encoded.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("ai-shell.master-key is not valid base64", e);
    }
    if (raw.length != 32) {
      throw new IllegalStateException(
          "ai-shell.master-key must decode to 32 bytes, got " + raw.length);
    }
    return new SecretKeySpec(raw, "AES");
  }
}
