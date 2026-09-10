package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/entitlement")
public class EntitlementsController {
  private final EntitlementService service;

  public EntitlementsController(EntitlementService service) {
    this.service = service;
  }

  @GetMapping
  public EntitlementService.Snapshot get(@RequestAttribute Actor actor) {
    return service.snapshot(actor);
  }

  @PutMapping
  public EntitlementService.Snapshot update(
      @RequestAttribute Actor actor, @RequestBody @Valid EntitlementService.PackageChange input) {
    return service.change(actor, input);
  }
}
