package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class DocumentPublicationController {
  private final DocumentPublicationService service;

  public DocumentPublicationController(DocumentPublicationService service) {
    this.service = service;
  }

  @GetMapping("/documents/{id}/publications")
  public java.util.List<java.util.Map<String, Object>> history(
      @RequestAttribute Actor actor, @PathVariable String id) {
    return service.history(actor, id);
  }

  @PostMapping("/documents/{id}/publications")
  public Object publish(
      @RequestAttribute Actor actor,
      @PathVariable String id,
      @RequestBody @Valid DocumentPublicationService.Publish body) {
    return service.publish(actor, id, body);
  }

  @DeleteMapping("/documents/{id}")
  public void delete(
      @RequestAttribute Actor actor, @PathVariable String id, @RequestParam long revision) {
    service.delete(actor, id, revision);
  }
}
