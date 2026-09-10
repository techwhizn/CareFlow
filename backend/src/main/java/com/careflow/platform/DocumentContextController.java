package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/document-versions/{version}")
public class DocumentContextController {
  private final DocumentContextService service;

  public DocumentContextController(DocumentContextService service) {
    this.service = service;
  }

  @PostMapping("/contexts/{context}/detach")
  public Object detach(
      @RequestAttribute Actor actor,
      @RequestHeader("Authorization") String authorization,
      @PathVariable String version,
      @PathVariable String context,
      @RequestBody @Valid DocumentContextService.Change input) {
    return service.detach(actor, authorization, version, context, input);
  }

  @PostMapping("/faqs")
  public Object create(
      @RequestAttribute Actor actor,
      @RequestHeader("Authorization") String authorization,
      @PathVariable String version,
      @RequestBody @Valid DocumentContextService.Faq input) {
    return service.saveFaq(actor, authorization, version, null, input);
  }

  @PutMapping("/faqs/{context}")
  public Object update(
      @RequestAttribute Actor actor,
      @RequestHeader("Authorization") String authorization,
      @PathVariable String version,
      @PathVariable String context,
      @RequestBody @Valid DocumentContextService.Faq input) {
    return service.saveFaq(actor, authorization, version, context, input);
  }
}
