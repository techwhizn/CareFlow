package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/documents/{id}")
public class DocumentMetadataController {
  private final DocumentMetadataService service;

  public DocumentMetadataController(DocumentMetadataService service) {
    this.service = service;
  }

  @GetMapping("/metadata")
  public Object get(@RequestAttribute Actor actor, @PathVariable String id) {
    return service.get(actor, id);
  }

  @PutMapping("/metadata")
  public Object update(
      @RequestAttribute Actor actor,
      @PathVariable String id,
      @RequestBody @Valid DocumentMetadataService.Metadata input) {
    return service.update(actor, id, input);
  }

  @GetMapping("/metadata-history")
  public Object history(
      @RequestAttribute Actor actor,
      @PathVariable String id,
      @RequestParam(defaultValue = "0") int page) {
    return service.history(actor, id, page);
  }
}
