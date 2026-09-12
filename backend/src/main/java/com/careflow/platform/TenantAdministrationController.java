package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tenant")
public class TenantAdministrationController {
  private final IdentityAdministrationService service;

  public TenantAdministrationController(IdentityAdministrationService service) {
    this.service = service;
  }

  @GetMapping
  public Object tenant(@RequestAttribute Actor actor) {
    return service.tenant(actor);
  }

  @PutMapping
  public Object change(
      @RequestAttribute Actor actor,
      @Valid @RequestBody IdentityAdministrationService.TenantChange input) {
    return service.changeTenant(actor, input);
  }
}
