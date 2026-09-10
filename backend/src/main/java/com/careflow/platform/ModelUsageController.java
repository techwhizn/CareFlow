package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ModelUsageController {
  private final ModelUsageReadService service;

  public ModelUsageController(ModelUsageReadService service) {
    this.service = service;
  }

  @GetMapping("/usage/models")
  public Object tenant(@RequestAttribute Actor actor) {
    return service.tenant(actor);
  }

  @GetMapping("/applications/{id}/usage")
  public Object application(@RequestAttribute Actor actor, @PathVariable String id) {
    return service.application(actor, id);
  }
}
