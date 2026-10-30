package com.aishell.model;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/models")
public class ModelController {

  private final ModelService models;

  public ModelController(ModelService models) {
    this.models = models;
  }

  @GetMapping
  public List<ModelService.View> list() {
    return models.list();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ModelService.View create(@RequestBody(required = false) ModelService.Input input) {
    return models.create(input);
  }

  @PutMapping("/{id}")
  public ModelService.View update(
      @PathVariable String id, @RequestBody(required = false) ModelService.Input input) {
    return models.update(id, input);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable String id) {
    models.delete(id);
  }
}
