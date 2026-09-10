package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class UsageAdministrationController {
  private final UsageAdministrationService service;

  public UsageAdministrationController(UsageAdministrationService service) {
    this.service = service;
  }

  @GetMapping("/usage")
  public Object usage(@RequestAttribute Actor actor) {
    return service.usage(actor);
  }

  @PutMapping("/quota")
  public void quota(
      @RequestAttribute Actor actor, @RequestBody @Valid UsageAdministrationService.Quota body) {
    service.quota(actor, body);
  }
}
