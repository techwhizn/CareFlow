package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ApplicationController {
  private final ApplicationService service;

  public ApplicationController(ApplicationService service) {
    this.service = service;
  }

  @GetMapping("/applications")
  public Object apps(@RequestAttribute Actor actor) {
    return service.apps(actor);
  }

  @PostMapping("/applications")
  public Object app(
      @RequestAttribute Actor actor, @RequestBody @Valid ApplicationService.Named body) {
    return service.app(actor, body);
  }

  @GetMapping("/applications/{id}/bindings")
  public Object bindings(@RequestAttribute Actor actor, @PathVariable String id) {
    return service.bindings(actor, id);
  }

  @PutMapping("/applications/{id}/publication")
  public void bind(
      @RequestAttribute Actor actor,
      @PathVariable String id,
      @RequestBody @Valid ApplicationService.Bind body) {
    service.bind(actor, id, body);
  }

  @PostMapping("/applications/{id}/configurations")
  public Object saveConfiguration(
      @RequestAttribute Actor actor,
      @PathVariable String id,
      @RequestBody @Valid ApplicationService.Bind body)
      throws Exception {
    return service.saveConfiguration(actor, id, body);
  }

  @GetMapping("/applications/{id}/configurations")
  public Object configurations(@RequestAttribute Actor actor, @PathVariable String id) {
    return service.configurations(actor, id);
  }

  @PostMapping("/applications/{id}/configuration-publications")
  public void publishConfiguration(
      @RequestAttribute Actor actor,
      @PathVariable String id,
      @RequestBody @Valid ApplicationService.ConfigurationPublish body)
      throws Exception {
    service.publishConfiguration(actor, id, body);
  }
}
