package com.aishell.file;

import com.aishell.terminal.TerminalSessions;
import com.jcraft.jsch.Session;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Read-only file browsing for the terminal's session: lazy listings and bounded previews. */
@RestController
@RequestMapping("/api/files")
public class FileController {

  private final FileService files;
  private final TerminalSessions terminals;

  public FileController(FileService files, TerminalSessions terminals) {
    this.files = files;
    this.terminals = terminals;
  }

  @GetMapping
  public FileService.FileListing list(
      @RequestParam("session") String session,
      @RequestParam(value = "path", defaultValue = ".") String path) {
    return files.list(liveSession(session), path);
  }

  @GetMapping("/preview")
  public FileService.FilePreview preview(
      @RequestParam("session") String session, @RequestParam("path") String path) {
    return files.preview(liveSession(session), path);
  }

  private Session liveSession(String session) {
    return terminals
        .findSshSession(session)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.CONFLICT, "terminal session is not connected"));
  }
}
