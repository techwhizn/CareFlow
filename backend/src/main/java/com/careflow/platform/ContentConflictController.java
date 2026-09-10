package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/document-versions/{version}")
public class ContentConflictController {
  private final ContentConflictService service;

  public ContentConflictController(ContentConflictService service) {
    this.service = service;
  }

  @GetMapping("/content-conflicts")
  public Object list(
      @RequestAttribute Actor actor,
      @PathVariable String version,
      @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page) {
    return service.list(actor, version, page);
  }

  @GetMapping("/chunk-changes")
  public Object history(
      @RequestAttribute Actor actor,
      @PathVariable String version,
      @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page) {
    return service.history(actor, version, page);
  }

  @PostMapping("/content-conflicts/{id}/resolution")
  public Object resolve(
      @RequestAttribute Actor actor,
      @RequestHeader("Authorization") String authorization,
      @PathVariable String version,
      @PathVariable String id,
      @RequestBody @Valid ContentConflictService.Resolution input) {
    return service.resolve(actor, authorization, version, id, input);
  }
}
