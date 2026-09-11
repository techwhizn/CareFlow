package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/integrations")
public class IntegrationsController {
  private final IntegrationService service;

  public IntegrationsController(IntegrationService service) {
    this.service = service;
  }

  @GetMapping
  public Object list(@RequestAttribute Actor actor) {
    return service.list(actor);
  }

  @PostMapping
  public Object create(
      @RequestAttribute Actor actor, @RequestBody @Valid IntegrationService.Create body) {
    return service.create(actor, body);
  }

  @DeleteMapping("/{id}")
  public void disable(
      @RequestAttribute Actor actor, @PathVariable String id, @RequestParam long revision) {
    service.disable(actor, id, revision);
  }

  @GetMapping("/{id}/deliveries")
  public Object deliveries(@RequestAttribute Actor actor, @PathVariable String id) {
    return service.deliveries(actor, id);
  }

  @PostMapping("/{id}/deliveries/{delivery}/retry")
  public void retry(
      @RequestAttribute Actor actor, @PathVariable String id, @PathVariable String delivery) {
    service.retry(actor, id, delivery);
  }
}
