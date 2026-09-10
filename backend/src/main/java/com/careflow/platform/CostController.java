package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class CostController {
  private final CostAccountingService service;

  public CostController(CostAccountingService service) {
    this.service = service;
  }

  @GetMapping({"/usage/requests/{id}/cost", "/applications/{app}/requests/{id}/cost"})
  public Object request(
      @RequestAttribute Actor actor,
      @PathVariable(required = false) String app,
      @PathVariable UUID id) {
    return service.request(actor, app, id);
  }

  @GetMapping("/jobs/{id}/cost")
  public Object job(@RequestAttribute Actor actor, @PathVariable UUID id) {
    return service.job(actor, id);
  }
}
