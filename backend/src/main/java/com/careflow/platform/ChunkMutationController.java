package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/document-versions/{version}/chunk-operations")
public class ChunkMutationController {
  private final ChunkMutationService service;

  public ChunkMutationController(ChunkMutationService service) {
    this.service = service;
  }

  @PostMapping
  public Object operate(
      @RequestAttribute Actor actor,
      @RequestHeader("Authorization") String authorization,
      @PathVariable String version,
      @RequestBody @Valid ChunkMutationService.Operation input) {
    return service.operate(actor, authorization, version, input);
  }
}
