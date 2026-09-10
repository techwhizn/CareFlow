package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cleanup-requests")
public class CleanupController {
  private final CleanupReadService service;

  public CleanupController(CleanupReadService service) {
    this.service = service;
  }

  @GetMapping
  public Object list(@RequestAttribute Actor actor) {
    return service.list(actor);
  }

  @PostMapping("/{id}/retry")
  public void retry(
      @RequestAttribute Actor actor,
      @PathVariable String id,
      @RequestBody @Valid CleanupReadService.Retry input) {
    service.retry(actor, id, input);
  }
}
