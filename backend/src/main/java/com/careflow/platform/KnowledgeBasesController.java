package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/knowledge-bases")
public class KnowledgeBasesController {
  private final KnowledgeBaseService service;
  private final KnowledgeLifecycleService lifecycle;

  public KnowledgeBasesController(
      KnowledgeBaseService service, KnowledgeLifecycleService lifecycle) {
    this.service = service;
    this.lifecycle = lifecycle;
  }

  @GetMapping
  public Object list(@RequestAttribute Actor actor) {
    return service.list(actor);
  }

  @PostMapping
  public Object create(
      @RequestAttribute Actor actor, @RequestBody @Valid KnowledgeBaseService.Create input) {
    return service.create(actor, input);
  }

  @GetMapping("/{id}")
  public Object get(@RequestAttribute Actor actor, @PathVariable String id) {
    return service.get(actor, id);
  }

  @GetMapping("/{id}/settings")
  public Object settings(@RequestAttribute Actor actor, @PathVariable String id) {
    return service.settings(actor, id);
  }

  @PutMapping("/{id}")
  public Object update(
      @RequestAttribute Actor actor,
      @PathVariable String id,
      @RequestBody @Valid KnowledgeBaseService.Attributes input) {
    return service.update(actor, id, input);
  }

  @GetMapping("/{id}/overview")
  public Object overview(@RequestAttribute Actor actor, @PathVariable String id) {
    return service.overview(actor, id);
  }

  @GetMapping("/{id}/impact")
  public Object impact(@RequestAttribute Actor actor, @PathVariable String id) {
    return lifecycle.impact(actor, id);
  }

  @PutMapping("/{id}/state")
  public Object state(
      @RequestAttribute Actor actor,
      @PathVariable String id,
      @RequestBody @Valid KnowledgeLifecycleService.StateChange input) {
    return lifecycle.change(actor, id, input);
  }
}
