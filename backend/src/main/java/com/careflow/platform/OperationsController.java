package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/operations")
public class OperationsController {
  private final OperationsStatusService service;

  public OperationsController(OperationsStatusService service) {
    this.service = service;
  }

  @GetMapping("/status")
  public Object status(@RequestAttribute Actor actor) {
    return service.status(actor);
  }
}
