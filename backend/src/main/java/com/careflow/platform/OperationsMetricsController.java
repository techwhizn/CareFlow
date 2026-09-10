package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/operations")
public class OperationsMetricsController {
  private final OperationsMetricsService service;

  public OperationsMetricsController(OperationsMetricsService service) {
    this.service = service;
  }

  @GetMapping("/metrics")
  public Object metrics(@RequestAttribute Actor actor) {
    return service.metrics(actor);
  }

  @GetMapping(value = "/prometheus", produces = "text/plain;version=0.0.4")
  public String prometheus(@RequestAttribute Actor actor) {
    return service.prometheus(actor);
  }
}
