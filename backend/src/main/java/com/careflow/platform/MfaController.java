package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/mfa")
public class MfaController {
  private final MfaService service;

  public MfaController(MfaService service) {
    this.service = service;
  }

  @GetMapping
  public MfaService.Status status(@RequestAttribute Actor actor) {
    return service.status(actor);
  }

  @PostMapping("/enroll")
  public Object enroll(@RequestAttribute Actor actor) {
    return service.enroll(actor);
  }

  @PostMapping("/enable")
  public MfaService.Status enable(
      @RequestAttribute Actor actor, @RequestBody MfaService.Code input) {
    return service.enable(actor, input);
  }

  @PostMapping("/disable")
  public MfaService.Status disable(
      @RequestAttribute Actor actor, @RequestBody MfaService.Code input) {
    return service.disable(actor, input);
  }
}
