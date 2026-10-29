package com.aishell.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aishell.file.FileService.FileEntry;
import com.aishell.file.FileService.FileListing;
import com.aishell.file.FileService.FilePreview;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class FileServiceSftpTest {

  private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, 3};

  @TempDir static Path root;

  private static SshServer sshd;
  private static Session session;
  private static final FileService files = new FileService();

  @BeforeAll
  static void startSftpServer() throws Exception {
    Files.createDirectories(root.resolve("dirA/sub"));
    Files.writeString(root.resolve("dirA/sub/file.txt"), "nested\n");
    Files.writeString(root.resolve("notes.txt"), "你好 SFTP\nline2\n");
    Files.write(root.resolve("bin.dat"), new byte[] {'a', 0, 'b'});
    Files.write(root.resolve("pic.png"), PNG);
    byte[] large = new byte[300_000];
    Arrays.fill(large, (byte) 'a');
    Files.write(root.resolve("huge.log"), large);
    Files.write(root.resolve("big.png"), new byte[FileService.IMAGE_LIMIT + 10]);
    Files.writeString(root.resolve(".hidden"), "x");

    sshd = SshServer.setUpDefaultServer();
    sshd.setHost("127.0.0.1");
    sshd.setPort(0);
    sshd.setKeyPairProvider(
        new SimpleGeneratorHostKeyProvider(Files.createTempFile("aishell-sftp-key", ".ser")));
    sshd.setPasswordAuthenticator(
        (user, password, ignored) -> "test".equals(user) && "secret".equals(password));
    sshd.setFileSystemFactory(new VirtualFileSystemFactory(root));
    sshd.setSubsystemFactories(java.util.List.of(new SftpSubsystemFactory()));
    sshd.start();

    JSch jsch = new JSch();
    session = jsch.getSession("test", "127.0.0.1", sshd.getPort());
    session.setPassword("secret");
    session.setConfig("StrictHostKeyChecking", "no");
    session.connect(10_000);
  }

  @AfterAll
  static void stopSftpServer() throws IOException {
    if (session != null) {
      session.disconnect();
    }
    if (sshd != null) {
      sshd.stop(true);
    }
  }

  @Test
  void listsDirectoriesFirstAndKeepsHiddenFiles() {
    FileListing listing = files.list(session, ".");

    assertThat(listing.path()).isEqualTo("/");
    assertThat(listing.truncated()).isFalse();
    assertThat(listing.entries())
        .extracting(FileEntry::name)
        .contains("dirA", "notes.txt", "bin.dat", ".hidden")
        .doesNotContain(".", "..");
    assertThat(listing.entries().get(0).name()).isEqualTo("dirA");
    assertThat(listing.entries()).filteredOn(entry -> entry.type().equals("dir")).hasSize(1);
  }

  @Test
  void reportsPermissionsAndOwnersForEachEntry() {
    FileListing listing = files.list(session, ".");

    FileEntry dir = entry(listing, "dirA");
    assertThat(dir.permissions()).startsWith("d").hasSize(10);
    assertThat(dir.owner()).isNotBlank();
    assertThat(dir.group()).isNotBlank();

    FileEntry file = entry(listing, "notes.txt");
    assertThat(file.permissions()).startsWith("-").hasSize(10);
    assertThat(file.owner()).isNotBlank();
    assertThat(file.group()).isNotBlank();
  }

  @Test
  void browsesNestedDirectoriesLazily() {
    FileEntry dirA = entry(files.list(session, "."), "dirA");
    assertThat(dirA.path()).isEqualTo("/dirA");

    FileEntry sub = entry(files.list(session, dirA.path()), "sub");
    assertThat(sub.type()).isEqualTo("dir");

    FileListing deepest = files.list(session, sub.path());
    assertThat(deepest.entries()).extracting(FileEntry::name).containsExactly("file.txt");
    assertThat(deepest.entries().get(0).path()).isEqualTo("/dirA/sub/file.txt");
    assertThat(deepest.entries().get(0).size()).isEqualTo(7);
  }

  @Test
  void previewsUtf8Text() {
    FilePreview preview = files.preview(session, "/notes.txt");

    assertThat(preview.kind()).isEqualTo("text");
    assertThat(preview.text()).isEqualTo("你好 SFTP\nline2\n");
    assertThat(preview.truncated()).isFalse();
    assertThat(preview.size()).isEqualTo(18);
  }

  @Test
  void truncatesHugeTextAtTheCap() {
    FilePreview preview = files.preview(session, "/huge.log");

    assertThat(preview.kind()).isEqualTo("text");
    assertThat(preview.truncated()).isTrue();
    assertThat(preview.text()).hasSize(FileService.TEXT_LIMIT);
    assertThat(preview.size()).isEqualTo(300_000);
  }

  @Test
  void previewsImagesAsBase64() {
    FilePreview preview = files.preview(session, "/pic.png");

    assertThat(preview.kind()).isEqualTo("image");
    assertThat(preview.mime()).isEqualTo("image/png");
    assertThat(Base64.getDecoder().decode(preview.data())).isEqualTo(PNG);
    assertThat(preview.text()).isNull();
  }

  @Test
  void refusesImagesOverTheSizeCap() {
    FilePreview preview = files.preview(session, "/big.png");

    assertThat(preview.kind()).isEqualTo("too-large");
    assertThat(preview.data()).isNull();
    assertThat(preview.size()).isEqualTo(FileService.IMAGE_LIMIT + 10);
  }

  @Test
  void marksBinaryFiles() {
    assertThat(files.preview(session, "/bin.dat").kind()).isEqualTo("binary");
  }

  @Test
  void rejectsDirectoriesAndMissingPaths() {
    assertStatus(HttpStatus.BAD_REQUEST, () -> files.preview(session, "/dirA"));
    assertStatus(HttpStatus.NOT_FOUND, () -> files.list(session, "/nope"));
    assertStatus(HttpStatus.NOT_FOUND, () -> files.preview(session, "/nope.txt"));
  }

  private static FileEntry entry(FileListing listing, String name) {
    return listing.entries().stream()
        .filter(item -> item.name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private static void assertStatus(HttpStatus expected, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(expected));
  }
}
