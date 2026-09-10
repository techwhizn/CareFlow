package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/document-versions/{version}/quality")
public class ChunkQualityController {
  private final ChunkQualityService service;

  public ChunkQualityController(ChunkQualityService service) {
    this.service = service;
  }

  @GetMapping
  public ChunkQualityService.Report inspect(
      @RequestAttribute Actor actor,
      @PathVariable String version,
      @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page) {
    return service.inspect(actor, version, page);
  }
}
