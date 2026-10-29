package com.aishell.host;

import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Validates host and profile input, encrypts secrets and resolves connection targets. */
@Service
public class HostService {

  public record ConnectTarget(
      String host, int port, String user, String identity, String secret) {}

  public record HostView(
      String id, String name, String address, int port, List<ProfileView> profiles) {}

  public record ProfileView(
      String id,
      String name,
      String username,
      String authType,
      String privateKeyPath,
      boolean hasSecret) {}

  public record HostRequest(String name, String address, Integer port) {}

  public record ProfileRequest(
      String name, String username, String authType, String privateKeyPath, String secret) {}

  private static final String PASSWORD = "password";
  private static final String PRIVATE_KEY = "private_key";

  private final HostStore store;
  private final SecretCipher cipher;

  public HostService(HostStore store, SecretCipher cipher) {
    this.store = store;
    this.cipher = cipher;
  }

  public List<HostView> list() {
    return store.listHosts().stream().map(this::toView).toList();
  }

  public HostView createHost(HostRequest request) {
    HostRequest valid = validateHost(request);
    HostStore.HostRow row = store.insertHost(valid.name(), valid.address(), valid.port());
    return toView(row);
  }

  public HostView updateHost(String id, HostRequest request) {
    HostRequest valid = validateHost(request);
    HostStore.HostRow row =
        store
            .updateHost(id, valid.name(), valid.address(), valid.port())
            .orElseThrow(() -> notFound("host not found"));
    return toView(row);
  }

  public void deleteHost(String id) {
    if (!store.deleteHost(id)) {
      throw notFound("host not found");
    }
  }

  public ProfileView addProfile(String hostId, ProfileRequest request) {
    store.findHost(hostId).orElseThrow(() -> notFound("host not found"));
    ProfileInput valid = validateProfile(request);
    String secret = encrypt(valid.secret());
    HostStore.ProfileRow row =
        store.insertProfile(
            hostId, valid.name(), valid.username(), valid.authType(), valid.keyPath(), secret);
    return toView(row);
  }

  public ProfileView updateProfile(String id, ProfileRequest request) {
    HostStore.ProfileRow existing =
        store.findProfile(id).orElseThrow(() -> notFound("profile not found"));
    ProfileInput valid = validateProfile(request);
    String secret = valid.secret() == null ? existing.secret() : encrypt(valid.secret());
    HostStore.ProfileRow row =
        store
            .updateProfile(
                id, valid.name(), valid.username(), valid.authType(), valid.keyPath(), secret)
            .orElseThrow(() -> notFound("profile not found"));
    return toView(row);
  }

  public void deleteProfile(String id) {
    if (!store.deleteProfile(id)) {
      throw notFound("profile not found");
    }
  }

  /** Resolves a profile into everything the SSH bridge needs; secrets never leave the backend. */
  public ConnectTarget resolve(String profileId) {
    HostStore.ProfileRow profile =
        store
            .findProfile(profileId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "profile not found"));
    HostStore.HostRow host =
        store
            .findHost(profile.hostId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "host not found"));
    String secret = profile.secret() == null ? null : decrypt(profile.secret());
    String identity = PRIVATE_KEY.equals(profile.authType()) ? profile.privateKeyPath() : null;
    return new ConnectTarget(host.address(), host.port(), profile.username(), identity, secret);
  }

  private ProfileInput validateProfile(ProfileRequest request) {
    if (request == null) {
      throw badRequest("profile body is required");
    }
    String name = requireText(request.name(), "name");
    String username = requireText(request.username(), "username");
    String authType =
        request.authType() == null ? "" : request.authType().trim().toLowerCase(Locale.ROOT);
    if (!PASSWORD.equals(authType) && !PRIVATE_KEY.equals(authType)) {
      throw badRequest("authType must be password or private_key");
    }
    String keyPath = null;
    if (PRIVATE_KEY.equals(authType)) {
      keyPath = requireText(request.privateKeyPath(), "privateKeyPath");
    }
    return new ProfileInput(name, username, authType, keyPath, request.secret());
  }

  private HostRequest validateHost(HostRequest request) {
    if (request == null) {
      throw badRequest("host body is required");
    }
    String name = requireText(request.name(), "name");
    String address = requireText(request.address(), "address");
    int port = request.port() == null ? 22 : request.port();
    if (port < 1 || port > 65535) {
      throw badRequest("port must be between 1 and 65535");
    }
    return new HostRequest(name, address, port);
  }

  /** Null means "keep the stored secret on update", an empty string means "clear it". */
  private String encrypt(String secret) {
    if (secret == null || secret.isEmpty()) {
      return null;
    }
    if (!cipher.available()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "credential encryption is not configured");
    }
    return cipher.encrypt(secret);
  }

  private String decrypt(String stored) {
    if (!cipher.available()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "credential encryption is not configured");
    }
    try {
      return cipher.decrypt(stored);
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "stored credential cannot be decrypted", e);
    }
  }

  private HostView toView(HostStore.HostRow host) {
    List<ProfileView> profiles =
        store.listProfiles(host.id()).stream().map(this::toView).toList();
    return new HostView(host.id(), host.name(), host.address(), host.port(), profiles);
  }

  private ProfileView toView(HostStore.ProfileRow profile) {
    return new ProfileView(
        profile.id(),
        profile.name(),
        profile.username(),
        profile.authType(),
        profile.privateKeyPath(),
        profile.secret() != null);
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw badRequest(field + " is required");
    }
    return value.trim();
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private record ProfileInput(
      String name, String username, String authType, String keyPath, String secret) {}
}
