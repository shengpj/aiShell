package com.aishell.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aishell.host.HostService.ConnectTarget;
import com.aishell.host.HostService.HostRequest;
import com.aishell.host.HostService.HostView;
import com.aishell.host.HostService.ProfileRequest;
import com.aishell.host.HostService.ProfileView;
import com.aishell.security.SecretCipher;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class HostServiceTest {

  private static final String KEY = Base64.getEncoder().encodeToString(filled(32));

  @TempDir Path dataDir;

  private HostService service;

  @BeforeEach
  void setUp() {
    service = serviceWithKey(KEY);
  }

  @Test
  void listsCreatedHostsAndProfilesAfterReopeningTheDatabase() {
    HostView host = service.createHost(new HostRequest("生产 Web", "10.0.0.5", 2222));
    service.addProfile(
        host.id(), new ProfileRequest("root", "root", "private_key", "C:/keys/app.pem", null));
    service.addProfile(host.id(), new ProfileRequest("deploy", "deploy", "password", null, "s3cret"));

    HostService reopened = serviceWithKey(KEY);

    HostView listed = reopened.list().get(0);
    assertThat(listed.name()).isEqualTo("生产 Web");
    assertThat(listed.address()).isEqualTo("10.0.0.5");
    assertThat(listed.port()).isEqualTo(2222);
    assertThat(listed.profiles()).hasSize(2);
    assertThat(listed.profiles())
        .extracting(ProfileView::name)
        .containsExactlyInAnyOrder("root", "deploy");
    assertThat(listed.profiles()).filteredOn(p -> p.name().equals("deploy"))
        .singleElement()
        .satisfies(p -> assertThat(p.hasSecret()).isTrue());
  }

  @Test
  void keepsAnExistingSecretWhenTheUpdateOmitsIt() {
    HostView host = service.createHost(new HostRequest("h", "10.0.0.5", 22));
    ProfileView profile =
        service.addProfile(host.id(), new ProfileRequest("p", "root", "password", null, "s3cret"));

    ProfileView updated =
        service.updateProfile(profile.id(), new ProfileRequest("renamed", "root", "password", null, null));

    assertThat(updated.name()).isEqualTo("renamed");
    assertThat(updated.hasSecret()).isTrue();
    assertThat(service.resolve(profile.id()).secret()).isEqualTo("s3cret");
  }

  @Test
  void clearsTheSecretWhenTheUpdateSendsAnEmptyString() {
    HostView host = service.createHost(new HostRequest("h", "10.0.0.5", 22));
    ProfileView profile =
        service.addProfile(host.id(), new ProfileRequest("p", "root", "password", null, "s3cret"));

    ProfileView updated =
        service.updateProfile(profile.id(), new ProfileRequest("p", "root", "password", null, ""));

    assertThat(updated.hasSecret()).isFalse();
    assertThat(service.resolve(profile.id()).secret()).isNull();
  }

  @Test
  void resolvesProfileIntoConnectionTarget() {
    HostView host = service.createHost(new HostRequest("h", "10.0.0.5", 2222));
    ProfileView password =
        service.addProfile(host.id(), new ProfileRequest("p", "root", "password", null, "s3cret"));
    ProfileView key =
        service.addProfile(
            host.id(), new ProfileRequest("k", "deploy", "private_key", "C:/keys/app.pem", "phrase"));

    ConnectTarget passwordTarget = service.resolve(password.id());
    assertThat(passwordTarget.host()).isEqualTo("10.0.0.5");
    assertThat(passwordTarget.port()).isEqualTo(2222);
    assertThat(passwordTarget.user()).isEqualTo("root");
    assertThat(passwordTarget.identity()).isNull();
    assertThat(passwordTarget.secret()).isEqualTo("s3cret");

    ConnectTarget keyTarget = service.resolve(key.id());
    assertThat(keyTarget.identity()).isEqualTo("C:/keys/app.pem");
    assertThat(keyTarget.secret()).isEqualTo("phrase");
  }

  @Test
  void softDeletingAHostHidesItsProfilesAndTarget() {
    HostView host = service.createHost(new HostRequest("h", "10.0.0.5", 22));
    ProfileView profile =
        service.addProfile(host.id(), new ProfileRequest("p", "root", "password", null, "s3cret"));

    service.deleteHost(host.id());

    assertThat(service.list()).isEmpty();
    assertThatThrownBy(() -> service.resolve(profile.id()))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND));
    assertThatThrownBy(() -> service.deleteHost(host.id()))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void listsTheNewestConnectionFirst() {
    HostView older = service.createHost(new HostRequest("older", "10.0.0.1", 22));
    service.addProfile(older.id(), new ProfileRequest("old", "root", "password", null, null));
    HostView newer = service.createHost(new HostRequest("newer", "10.0.0.2", 22));
    service.addProfile(newer.id(), new ProfileRequest("new", "root", "password", null, null));

    assertThat(service.list()).extracting(HostView::id)
        .containsExactly(newer.id(), older.id());

    service.addProfile(older.id(), new ProfileRequest("newest", "root", "password", null, null));

    assertThat(service.list()).extracting(HostView::id)
        .containsExactly(older.id(), newer.id());
    assertThat(service.list().get(0).profiles()).extracting(ProfileView::name)
        .containsExactly("newest", "old");
  }

  @Test
  void deletingTheLastProfileRetiresTheHost() {
    HostView host = service.createHost(new HostRequest("h", "10.0.0.5", 22));
    ProfileView profile =
        service.addProfile(host.id(), new ProfileRequest("p", "root", "password", null, "s3cret"));

    service.deleteProfile(profile.id());

    assertThat(service.list()).isEmpty();
    assertThatThrownBy(() -> service.resolve(profile.id()))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND));
    assertThatThrownBy(() -> service.deleteHost(host.id()))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void deletingOneOfTwoProfilesKeepsTheHost() {
    HostView host = service.createHost(new HostRequest("h", "10.0.0.5", 22));
    ProfileView keep =
        service.addProfile(host.id(), new ProfileRequest("keep", "root", "password", null, null));
    ProfileView remove =
        service.addProfile(host.id(), new ProfileRequest("remove", "root", "password", null, null));

    service.deleteProfile(remove.id());

    assertThat(service.list()).singleElement().satisfies(remaining -> {
      assertThat(remaining.id()).isEqualTo(host.id());
      assertThat(remaining.profiles()).extracting(ProfileView::id).containsExactly(keep.id());
    });
  }

  @Test
  void refusesSecretsWithoutAMasterKeyButStillStoresPublicFields() {
    HostService locked = serviceWithKey("");
    HostView host = locked.createHost(new HostRequest("h", "10.0.0.5", 22));

    ProfileView profile =
        locked.addProfile(host.id(), new ProfileRequest("p", "root", "password", null, null));
    assertThat(profile.hasSecret()).isFalse();

    assertThatThrownBy(
            () ->
                locked.addProfile(
                    host.id(), new ProfileRequest("p2", "root", "password", null, "s3cret")))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

    service.addProfile(host.id(), new ProfileRequest("p3", "root", "password", null, "s3cret"));
    assertThatThrownBy(() -> locked.resolve(service.list().stream()
            .flatMap(h -> h.profiles().stream())
            .filter(p -> p.name().equals("p3"))
            .findFirst()
            .orElseThrow()
            .id()))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
  }

  @Test
  void validatesHostAndProfileInput() {
    assertStatus(HttpStatus.BAD_REQUEST, () -> service.createHost(new HostRequest("", "a", 22)));
    assertStatus(HttpStatus.BAD_REQUEST, () -> service.createHost(new HostRequest("n", "a", 0)));
    assertStatus(HttpStatus.BAD_REQUEST, () -> service.createHost(new HostRequest("n", "a", 70000)));

    HostView host = service.createHost(new HostRequest("h", "10.0.0.5", 22));
    assertStatus(
        HttpStatus.BAD_REQUEST,
        () -> service.addProfile(host.id(), new ProfileRequest("p", "", "password", null, null)));
    assertStatus(
        HttpStatus.BAD_REQUEST,
        () -> service.addProfile(host.id(), new ProfileRequest("p", "u", "token", null, null)));
    assertStatus(
        HttpStatus.BAD_REQUEST,
        () -> service.addProfile(host.id(), new ProfileRequest("p", "u", "private_key", "", null)));
  }

  private HostService serviceWithKey(String key) {
    return new HostService(new HostStore(dataDir.toString()), new SecretCipher(key));
  }

  private static void assertStatus(HttpStatus expected, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(expected));
  }

  private static byte[] filled(int length) {
    byte[] value = new byte[length];
    for (int i = 0; i < length; i++) {
      value[i] = (byte) i;
    }
    return value;
  }
}
