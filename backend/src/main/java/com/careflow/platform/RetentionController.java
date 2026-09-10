package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/retention")
public class RetentionController {
  private final RetentionService service;

  public RetentionController(RetentionService service) {
    this.service = service;
  }

  @GetMapping
  public Object get(@RequestAttribute Actor actor) {
    return service.read(actor);
  }

  @PutMapping
  public Object put(
      @RequestAttribute Actor actor, @Valid @RequestBody RetentionService.Input input) {
    return service.update(actor, input);
  }
}
