package com.aishell.runbook;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/runbooks")
public class RunbookController {
  private final RunbookStore store;

  public RunbookController(RunbookStore store) { this.store = store; }

  @GetMapping
  public List<RunbookStore.Runbook> list() { return store.list(); }

  @PostMapping
  public RunbookStore.Runbook create(@RequestBody(required = false) RunbookRequest request) {
    validate(request);
    return store.insert(request.title().trim(), request.summary().trim(), request.category().trim(),
        request.fields(), request.steps(), request.success());
  }

  @PutMapping("/{id}")
  public RunbookStore.Runbook update(@PathVariable String id, @RequestBody(required = false) RunbookRequest request) {
    validate(request);
    return store.update(id, request.title().trim(), request.summary().trim(), request.category().trim(),
            request.fields(), request.steps(), request.success(), request.version())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "runbook not found or version changed"));
  }

  @DeleteMapping("/{id}")
  public void delete(@PathVariable String id) {
    if (!store.delete(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "runbook not found");
  }

  private static void validate(RunbookRequest request) {
    if (request == null || blank(request.title()) || blank(request.summary()) || blank(request.category())
        || request.steps() == null || request.steps().isEmpty() || request.success() == null || request.success().isEmpty()
        || request.fields() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title, summary, category, fields, steps and success are required");
    }
    if (request.title().length() > 120 || request.summary().length() > 500 || request.category().length() > 80
        || request.steps().size() > 50 || request.success().size() > 20 || request.fields().size() > 20) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "runbook is too large");
    }
    request.steps().forEach(step -> { if (blank(step) || step.length() > 1000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid step"); });
    request.success().forEach(item -> { if (blank(item) || item.length() > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid success criterion"); });
    request.fields().forEach(field -> { if (field == null || blank(field.id()) || blank(field.label()) || field.id().length() > 40 || field.label().length() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid field"); });
  }

  private static boolean blank(String value) { return value == null || value.isBlank(); }

  public record RunbookRequest(String title, String summary, String category,
      List<RunbookStore.Field> fields, List<String> steps, List<String> success, int version) {}
}
