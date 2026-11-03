package com.aishell.chat;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.ObjectMapper;

/** Global audit search and export. File events remain distinct from shell command history. */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

  private final AuditLog audit;
  private final ObjectMapper json;

  public AuditController(AuditLog audit, ObjectMapper json) {
    this.audit = audit;
    this.json = json;
  }

  @GetMapping
  public AuditLog.AuditPage search(
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String session,
      @RequestParam(required = false) String initiator,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String query,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return audit.search(new AuditLog.AuditFilter(parse(from), parse(to), session, initiator, kind,
        status, query, Math.max(0, page), Math.max(1, Math.min(size, 200))));
  }

  @GetMapping("/export")
  public ResponseEntity<StreamingResponseBody> export(
      @RequestParam(defaultValue = "json") String format,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String session,
      @RequestParam(required = false) String initiator,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String query) {
    if (!format.equalsIgnoreCase("json") && !format.equalsIgnoreCase("csv")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "format must be json or csv");
    }
    AuditLog.AuditFilter filter = new AuditLog.AuditFilter(parse(from), parse(to), session,
        initiator, kind, status, query, 0, 200);
    StreamingResponseBody body = output -> {
      StringBuilder buffer = new StringBuilder();
      if (format.equalsIgnoreCase("csv")) {
        buffer.append("at,kind,initiator,session,command,path,status,exitCode,size,durationMs,message,reason,output,source\n");
        audit.writeMatches(filter, entry -> writeCsv(buffer, output, entry));
      } else {
        output.write('[');
        final boolean[] first = {true};
        audit.writeMatches(filter, entry -> {
          try {
            if (!first[0]) output.write(',');
            output.write(json.writeValueAsBytes(entry));
            first[0] = false;
          } catch (java.io.IOException e) {
            throw new RuntimeException(e);
          }
        });
        output.write(']');
      }
    };
    String extension = format.equalsIgnoreCase("csv") ? "csv" : "json";
    return ResponseEntity.ok()
        .contentType(format.equalsIgnoreCase("csv") ? MediaType.parseMediaType("text/csv") : MediaType.APPLICATION_JSON)
        .header(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename("ai-shell-audit." + extension).build().toString())
        .body(body);
  }

  private static Instant parse(String value) {
    if (value == null || value.isBlank()) return null;
    try { return Instant.parse(value); }
    catch (RuntimeException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid timestamp: " + value); }
  }

  private static void writeCsv(StringBuilder buffer, java.io.OutputStream output, Map<String, Object> entry) {
    String[] keys = {"at", "kind", "initiator", "session", "command", "path", "status", "exitCode", "size", "durationMs", "message", "reason", "output", "source"};
    for (int i = 0; i < keys.length; i++) {
      if (i > 0) buffer.append(',');
      String value = String.valueOf(entry.getOrDefault(keys[i], ""));
      buffer.append('"').append(value.replace("\"", "\"\"")).append('"');
    }
    buffer.append('\n');
    try {
      output.write(buffer.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      buffer.setLength(0);
    } catch (java.io.IOException e) { throw new RuntimeException(e); }
  }

}
