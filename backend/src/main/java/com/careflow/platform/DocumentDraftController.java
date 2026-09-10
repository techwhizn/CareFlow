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
      @RequestHeader("Authorization") String authorization,
      @PathVariable String id,
      @RequestBody @Valid DocumentDraftService.Edit body) {
    return service.edit(actor, authorization, id, body);
  }

  @PostMapping("/document-versions/{id}/draft")
  public Object draft(@RequestAttribute Actor actor, @PathVariable String id) {
    return service.draft(actor, id);
  }

  @PostMapping("/document-versions/{id}/configuration-binding")
  public Object bindConfiguration(
      @RequestAttribute Actor actor,
      @PathVariable String id,
      @RequestBody @Valid DocumentDraftService.BindConfiguration input) {
    return service.bindConfiguration(actor, id, input);
  }

  @PostMapping("/document-versions/{id}/reprocess")
  public Object reprocess(
      @RequestAttribute Actor actor,
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key) {
    return service.reprocess(actor, id, key);
  }

  @PostMapping("/document-versions/{id}/index")
  public Object index(
      @RequestAttribute Actor actor,
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key) {
    return service.index(actor, id, key);
  }
}
