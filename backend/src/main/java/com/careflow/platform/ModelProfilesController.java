package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/model-profiles")
public class ModelProfilesController {
  private final ModelProfileService service;

  public ModelProfilesController(ModelProfileService service) {
    this.service = service;
  }

  @GetMapping
  public List<ModelProfileService.View> list(@RequestAttribute Actor actor) {
    return service.list(actor);
  }

  @GetMapping("/policy")
  public Map<String, Object> policy(@RequestAttribute Actor actor) {
    return service.policy(actor);
  }

  @PostMapping
  public ModelProfileService.View create(
      @RequestAttribute Actor actor, @RequestBody @Valid ModelProfileService.Input input) {
    return service.save(actor, null, input);
  }

  @PutMapping("/{id}")
  public ModelProfileService.View update(
      @RequestAttribute Actor actor,
      @PathVariable UUID id,
      @RequestBody @Valid ModelProfileService.Input input) {
    return service.save(actor, id.toString(), input);
  }
}
