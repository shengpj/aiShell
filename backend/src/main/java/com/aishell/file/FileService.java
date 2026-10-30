package com.aishell.file;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Vector;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Remote file access over an SFTP channel opened on the terminal's SSH session. Listings and
 * previews are bounded; writes are restricted to existing UTF-8 text files and use optimistic
 * version checking.
 */
@Service
public class FileService {

  public record FileEntry(
      String name,
      String path,
      String type,
      long size,
      long modifiedAt,
      String permissions,
      String owner,
      String group) {}

  public record FileListing(String path, List<FileEntry> entries, boolean truncated) {}

  public record FilePreview(
      String path,
      String kind,
      long size,
      long modifiedAt,
      String mime,
      String text,
      String data,
      boolean truncated) {}

  public record FileWriteResult(String path, long size, long modifiedAt) {}

  public record FileDownload(String path, String name, String mime, byte[] data) {}

  static final int MAX_ENTRIES = 2000;
  static final int TEXT_LIMIT = 256 * 1024;
  static final int IMAGE_LIMIT = 4 * 1024 * 1024;

  private static final Map<String, String> IMAGE_TYPES =
      Map.of(
          "png", "image/png",
          "jpg", "image/jpeg",
          "jpeg", "image/jpeg",
          "gif", "image/gif",
          "webp", "image/webp",
          "bmp", "image/bmp");

  private static final Comparator<FileEntry> ENTRY_ORDER =
      Comparator.comparing((FileEntry entry) -> !entry.type().equals("dir"))
          .thenComparing(FileEntry::name, String.CASE_INSENSITIVE_ORDER)
          .thenComparing(FileEntry::name);

  public FileListing list(Session session, String path) {
    ChannelSftp channel = open(session);
    String target = path == null || path.isBlank() ? "." : path.trim();
    try {
      String canonical = channel.realpath(target);
      Vector<ChannelSftp.LsEntry> items = channel.ls(canonical);
      List<FileEntry> entries = new ArrayList<>(items.size());
      boolean truncated = false;
      for (ChannelSftp.LsEntry item : items) {
        String name = item.getFilename();
        if (name.equals(".") || name.equals("..")) {
          continue;
        }
        if (entries.size() >= MAX_ENTRIES) {
          truncated = true;
          break;
        }
        SftpATTRS attrs = item.getAttrs();
        OwnerNames owner = ownerNames(attrs, item.getLongname());
        entries.add(
            new FileEntry(
                name,
                childPath(canonical, name),
                typeOf(attrs),
                attrs.getSize(),
                attrs.getMTime(),
                attrs.getPermissionsString(),
                owner.user(),
                owner.group()));
      }
      entries.sort(ENTRY_ORDER);
      return new FileListing(canonical, entries, truncated);
    } catch (SftpException e) {
      throw failure(e, target);
    } finally {
      channel.disconnect();
    }
  }

  public FilePreview preview(Session session, String path) {
    if (path == null || path.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path is required");
    }
    String target = path.trim();
    ChannelSftp channel = open(session);
    try {
      SftpATTRS attrs = channel.lstat(target);
      if (attrs.isDir()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path is a directory");
      }
      if (!attrs.isReg()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path is not a regular file");
      }
      long size = attrs.getSize();
      String mime = imageMime(target);
      if (mime != null) {
        if (size > IMAGE_LIMIT) {
          return new FilePreview(target, "too-large", size, attrs.getMTime(), mime, null, null, false);
        }
        byte[] data = read(channel, target, IMAGE_LIMIT);
        return new FilePreview(
            target,
            "image",
            size,
            attrs.getMTime(),
            mime,
            null,
            Base64.getEncoder().encodeToString(data),
            false);
      }
      byte[] bytes = read(channel, target, TEXT_LIMIT + 1);
      boolean truncated = bytes.length > TEXT_LIMIT;
      if (truncated) {
        bytes = Arrays.copyOf(bytes, TEXT_LIMIT);
      }
      if (containsNul(bytes)) {
        return new FilePreview(target, "binary", size, attrs.getMTime(), null, null, null, false);
      }
      String text = decodeUtf8(bytes, truncated);
      if (text == null) {
        return new FilePreview(target, "binary", size, attrs.getMTime(), null, null, null, false);
      }
      return new FilePreview(
          target, "text", size, attrs.getMTime(), "text/plain; charset=utf-8", text, null, truncated);
    } catch (SftpException e) {
      throw failure(e, target);
    } catch (IOException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "failed to read " + target + ": " + e.getMessage(), e);
    } finally {
      channel.disconnect();
    }
  }

  public FileWriteResult writeText(
      Session session, String path, String content, Long expectedModifiedAt) {
    if (path == null || path.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path is required");
    }
    if (content == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content is required");
    }
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > TEXT_LIMIT) {
      throw new ResponseStatusException(
          HttpStatus.PAYLOAD_TOO_LARGE, "text file is limited to " + TEXT_LIMIT + " bytes");
    }
    String target = path.trim();
    ChannelSftp channel = open(session);
    String temporary = target + ".aishell-tmp-" + UUID.randomUUID();
    try {
      SftpATTRS attrs = channel.lstat(target);
      if (!attrs.isReg() || attrs.isLink()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path is not a regular file");
      }
      if (expectedModifiedAt != null && expectedModifiedAt >= 0 && attrs.getMTime() != expectedModifiedAt) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "file changed on the remote host; reload it before saving");
      }
      channel.put(new ByteArrayInputStream(bytes), temporary, ChannelSftp.OVERWRITE);
      channel.chmod(attrs.getPermissions(), temporary);
      channel.rename(temporary, target);
      SftpATTRS saved = channel.stat(target);
      return new FileWriteResult(target, saved.getSize(), saved.getMTime());
    } catch (ResponseStatusException e) {
      throw e;
    } catch (SftpException e) {
      throw failure(e, target);
    } finally {
      try {
        channel.rm(temporary);
      } catch (SftpException ignored) {
        // The temporary file is already gone after a successful rename.
      }
      channel.disconnect();
    }
  }

  public FileDownload download(Session session, String path) {
    if (path == null || path.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path is required");
    }
    String target = path.trim();
    ChannelSftp channel = open(session);
    try {
      SftpATTRS attrs = channel.lstat(target);
      if (!attrs.isReg() || attrs.isLink()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path is not a regular file");
      }
      if (attrs.getSize() > IMAGE_LIMIT) {
        throw new ResponseStatusException(
            HttpStatus.PAYLOAD_TOO_LARGE, "download is limited to " + IMAGE_LIMIT + " bytes");
      }
      byte[] data = read(channel, target, IMAGE_LIMIT + 1);
      if (data.length > IMAGE_LIMIT) {
        throw new ResponseStatusException(
            HttpStatus.PAYLOAD_TOO_LARGE, "download is limited to " + IMAGE_LIMIT + " bytes");
      }
      String name = target.substring(target.lastIndexOf('/') + 1);
      return new FileDownload(target, name.isBlank() ? "download" : name, downloadMime(target), data);
    } catch (ResponseStatusException e) {
      throw e;
    } catch (SftpException e) {
      throw failure(e, target);
    } catch (IOException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "failed to read " + target + ": " + e.getMessage(), e);
    } finally {
      channel.disconnect();
    }
  }

  private static ChannelSftp open(Session session) {
    try {
      ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
      channel.connect(10_000);
      return channel;
    } catch (JSchException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "SFTP is not available on this connection: " + e.getMessage(), e);
    }
  }

  private static byte[] read(ChannelSftp channel, String path, int limit)
      throws SftpException, IOException {
    try (InputStream in = channel.get(path)) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int read;
      while (out.size() < limit
          && (read = in.read(buffer, 0, Math.min(buffer.length, limit - out.size()))) != -1) {
        out.write(buffer, 0, read);
      }
      return out.toByteArray();
    }
  }

  private static boolean containsNul(byte[] bytes) {
    for (byte value : bytes) {
      if (value == 0) {
        return true;
      }
    }
    return false;
  }

  /** Strict UTF-8; a capped read may end mid-character, so up to three trailing bytes are dropped. */
  private static String decodeUtf8(byte[] bytes, boolean mayBeCut) {
    CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    int maxTrim = mayBeCut ? Math.min(3, bytes.length) : 0;
    for (int trim = 0; trim <= maxTrim; trim++) {
      try {
        return decoder.reset()
            .decode(ByteBuffer.wrap(bytes, 0, bytes.length - trim))
            .toString();
      } catch (CharacterCodingException e) {
        // stay in the loop; only a truncated read is allowed to lose trailing bytes
      }
    }
    return null;
  }

  private static String imageMime(String path) {
    int dot = path.lastIndexOf('.');
    if (dot < 0 || dot == path.length() - 1) {
      return null;
    }
    return IMAGE_TYPES.get(path.substring(dot + 1).toLowerCase(Locale.ROOT));
  }

  private static String downloadMime(String path) {
    String image = imageMime(path);
    if (image != null) {
      return image;
    }
    String lower = path.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".json")) return "application/json";
    if (lower.endsWith(".xml")) return "application/xml";
    if (lower.endsWith(".csv")) return "text/csv";
    if (lower.endsWith(".log") || lower.endsWith(".txt") || lower.endsWith(".conf")) {
      return "text/plain; charset=utf-8";
    }
    return "application/octet-stream";
  }

  private static String typeOf(SftpATTRS attrs) {
    if (attrs.isDir()) {
      return "dir";
    }
    return attrs.isLink() ? "link" : "file";
  }

  private record OwnerNames(String user, String group) {}

  /**
   * SFTP has no call that resolves uid/gid to names; OpenSSH's sftp-server puts the names into
   * the longname (`-rw-r--r-- 1 root root 4096 Oct 28 12:00 name`), so parse them from there and
   * fall back to the numeric ids when a server omits them.
   */
  private static OwnerNames ownerNames(SftpATTRS attrs, String longname) {
    if (longname != null && !longname.isBlank()) {
      String[] parts = longname.trim().split("\\s+");
      if (parts.length >= 8) {
        return new OwnerNames(parts[2], parts[3]);
      }
    }
    return new OwnerNames(String.valueOf(attrs.getUId()), String.valueOf(attrs.getGId()));
  }

  private static String childPath(String parent, String name) {
    return parent.endsWith("/") ? parent + name : parent + "/" + name;
  }

  private static ResponseStatusException failure(SftpException e, String path) {
    if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
      return new ResponseStatusException(HttpStatus.NOT_FOUND, "path not found: " + path, e);
    }
    if (e.id == ChannelSftp.SSH_FX_PERMISSION_DENIED) {
      return new ResponseStatusException(HttpStatus.FORBIDDEN, "permission denied: " + path, e);
    }
    return new ResponseStatusException(
        HttpStatus.BAD_GATEWAY, "SFTP error at " + path + ": " + e.getMessage(), e);
  }
}
