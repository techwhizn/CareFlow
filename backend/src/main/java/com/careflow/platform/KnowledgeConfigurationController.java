package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/knowledge-bases/{kb}")
public class KnowledgeConfigurationController {
  private final KnowledgeConfigurationService service;
  private final ModelProfileService models;

  public KnowledgeConfigurationController(
      KnowledgeConfigurationService service, ModelProfileService models) {
    this.service = service;
    this.models = models;
  }

  @GetMapping("/configurations")
  public Object list(@RequestAttribute Actor actor, @PathVariable String kb) {
    return service.list(actor, kb);
  }

  @PostMapping("/configurations")
  public Object create(
      @RequestAttribute Actor actor,
      @PathVariable String kb,
      @RequestBody @Valid KnowledgeConfiguration.Definition definition) {
    return service.create(actor, kb, definition);
  }

  @GetMapping("/configuration-models")
  public Object models(@RequestAttribute Actor actor, @PathVariable String kb) {
    return models.forKnowledgeConfiguration(actor, kb);
  }

  @GetMapping("/configurations/{id}/impact")
  public Object impact(
      @RequestAttribute Actor actor, @PathVariable String kb, @PathVariable String id) {
    return service.impact(actor, kb, id);
  }

  @PostMapping("/configuration-publications")
  public Object publish(
      @RequestAttribute Actor actor,
      @PathVariable String kb,
      @RequestBody @Valid KnowledgeConfiguration.Publish input) {
    return service.publish(actor, kb, input);
  }
}
