package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationsController {
  public record Create(
      @Size(max = 36) String application_id, @Size(max = 20) List<String> knowledge_base_ids) {}

  private final ConversationService service;

  public ConversationsController(ConversationService service) {
    this.service = service;
  }

  @PostMapping
  public Object create(@RequestAttribute Actor actor, @RequestBody @Valid Create input) {
    return service.create(actor, input.application_id(), input.knowledge_base_ids());
  }

  @GetMapping
  public Object list(@RequestAttribute Actor actor) {
    return service.list(actor);
  }

  @GetMapping("/{id}")
  public Object get(@RequestAttribute Actor actor, @PathVariable UUID id) {
    return service.get(actor, id.toString());
  }
}
