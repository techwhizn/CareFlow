package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class DocumentReadController {
  private final DocumentReadService service;

  public DocumentReadController(DocumentReadService service) {
    this.service = service;
  }

  @GetMapping("/knowledge-bases/{id}/documents")
  public Object documents(
      @RequestAttribute Actor actor,
      @PathVariable String id,
      @RequestParam(defaultValue = "0") @Min(0) int page) {
    return service.documents(actor, id, page);
  }

  @GetMapping("/documents/{id}/versions")
  public Object versions(@RequestAttribute Actor actor, @PathVariable String id) {
    return service.versions(actor, id);
  }

  @GetMapping("/document-versions/{id}/chunks")
  public Object chunks(
      @RequestAttribute Actor actor,
      @PathVariable String id,
      @RequestParam(defaultValue = "0") int page) {
    return service.chunks(actor, id, page);
  }

  @GetMapping("/document-versions/{id}/contexts")
  public Object contexts(
      @RequestAttribute Actor actor,
      @PathVariable String id,
      @RequestParam(defaultValue = "0") @Min(0) int page) {
    return service.contexts(actor, id, page);
  }

  @GetMapping("/document-versions/{id}/contexts/{context}")
  public Object context(
      @RequestAttribute Actor actor, @PathVariable String id, @PathVariable String context) {
    return service.context(actor, id, context);
  }
}
