package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/billing")
public class BillingRulesController {
  private final BillingRulesService service;

  public BillingRulesController(BillingRulesService service) {
    this.service = service;
  }

  @GetMapping("/rules")
  public Object list(@RequestAttribute Actor actor) {
    return service.list(actor);
  }

  @PostMapping("/rules")
  public Object create(
      @RequestAttribute Actor actor, @RequestBody @Valid BillingRulesService.Input input) {
    return service.create(actor, input);
  }

  @PutMapping("/active-rule")
  public Object activate(
      @RequestAttribute Actor actor, @RequestBody @Valid BillingRulesService.Activate input) {
    return service.activate(actor, input);
  }
}
