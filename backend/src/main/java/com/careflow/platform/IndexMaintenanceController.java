package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/document-versions/{id}/index")
public class IndexMaintenanceController {
  private final IndexMaintenanceService service;

  public IndexMaintenanceController(IndexMaintenanceService service) {
    this.service = service;
  }

  @PostMapping("/rebuild")
  public Object rebuild(
      @RequestAttribute Actor actor,
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody @Valid IndexMaintenanceService.Rebuild input) {
    return service.rebuild(actor, id, key, input);
  }

  @PostMapping("/checks")
  public Object inspect(
      @RequestAttribute Actor actor,
      @PathVariable String id,
      @RequestHeader("Authorization") String authorization) {
    return service.inspect(actor, authorization, id);
  }

  @GetMapping("/history")
  public Object history(@RequestAttribute Actor actor, @PathVariable String id) {
    return service.history(actor, id);
  }
}
