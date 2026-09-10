package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class IdentityAdministrationController {
  private final IdentityAdministrationService service;

  public IdentityAdministrationController(IdentityAdministrationService service) {
    this.service = service;
  }

  @GetMapping("/me")
  public Object me(@RequestAttribute Actor actor) {
    return service.me(actor);
  }

  @GetMapping("/credentials")
  public Object keys(@RequestAttribute Actor actor) {
    return service.keys(actor);
  }

  @DeleteMapping("/credentials/{id}")
  public void revoke(@RequestAttribute Actor actor, @PathVariable String id) {
    service.revoke(actor, id);
  }

  @GetMapping("/audit")
  public Object audit(@RequestAttribute Actor actor) {
    return service.audit(actor);
  }
}
