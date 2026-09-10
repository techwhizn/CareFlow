package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/improvements")
public class ImprovementsController {
  private final ImprovementService service;

  public ImprovementsController(ImprovementService service) {
    this.service = service;
  }

  @PostMapping
  public Object create(
      @RequestAttribute Actor actor, @RequestBody @Valid ImprovementService.Create input) {
    return service.create(actor, input);
  }

  @GetMapping
  public Object list(@RequestAttribute Actor actor) {
    return service.list(actor);
  }

  @GetMapping("/{id}")
  public Object get(@RequestAttribute Actor actor, @PathVariable UUID id) {
    return service.get(actor, id.toString());
  }

  @GetMapping("/{id}/assignees")
  public Object assignees(@RequestAttribute Actor actor, @PathVariable UUID id) {
    return service.assignees(actor, id.toString());
  }

  @PutMapping("/{id}")
  public Object update(
      @RequestAttribute Actor actor,
      @PathVariable UUID id,
      @RequestBody @Valid ImprovementService.Update input) {
    return service.update(actor, id.toString(), input);
  }
}
