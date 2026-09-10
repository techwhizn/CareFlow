package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class DocumentDraftController {
  private final DocumentDraftService service;

  public DocumentDraftController(DocumentDraftService service) {
    this.service = service;
  }

  @PutMapping("/chunks/{id}")
  public Object edit(
      @RequestAttribute Actor actor,
      @PathVariable String id,
      @RequestBody @Valid DocumentDraftService.Edit body) {
    return service.edit(actor, id, body);
  }

  @PostMapping("/document-versions/{id}/draft")
  public Object draft(@RequestAttribute Actor actor, @PathVariable String id) {
    return service.draft(actor, id);
  }

  @PostMapping("/document-versions/{id}/index")
  public Object index(
      @RequestAttribute Actor actor,
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key) {
    return service.index(actor, id, key);
  }
}
