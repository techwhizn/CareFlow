package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/applications/{id}/limits")
public class ApplicationLimitsController {
  private final ApplicationAdmissionService service;

  public ApplicationLimitsController(ApplicationAdmissionService service) {
    this.service = service;
  }

  @GetMapping
  public Object get(@RequestAttribute Actor actor, @PathVariable String id) {
    return service.limits(actor, id);
  }

  @PutMapping
  public Object update(
      @RequestAttribute Actor actor,
      @PathVariable String id,
      @RequestBody @Valid ApplicationAdmissionService.Limits body) {
    return service.update(actor, id, body);
  }
}
