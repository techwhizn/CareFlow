package com.careflow.platform;

import com.careflow.platform.AuthorizationService.Resource;
import com.careflow.platform.Identity.Actor;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AuthorizationController {
  private final AuthorizationService service;

  public AuthorizationController(AuthorizationService service) {
    this.service = service;
  }

  @GetMapping("/knowledge-bases/{id}/permissions")
  public Object kbGrants(@RequestAttribute Actor actor, @PathVariable UUID id) {
    return service.grants(actor, id.toString(), Resource.KNOWLEDGE_BASE);
  }

  @GetMapping("/documents/{id}/permissions")
  public Object docGrants(@RequestAttribute Actor actor, @PathVariable UUID id) {
    return service.grants(actor, id.toString(), Resource.DOCUMENT);
  }

  @GetMapping("/knowledge-bases/{id}/authorization")
  public Object kbSnapshot(@RequestAttribute Actor actor, @PathVariable UUID id) {
    return service.snapshot(actor, id.toString(), Resource.KNOWLEDGE_BASE);
  }

  @GetMapping("/documents/{id}/authorization")
  public Object docSnapshot(@RequestAttribute Actor actor, @PathVariable UUID id) {
    return service.snapshot(actor, id.toString(), Resource.DOCUMENT);
  }

  @PutMapping("/knowledge-bases/{id}/permissions")
  public void kbReplace(
      @RequestAttribute Actor actor,
      @PathVariable UUID id,
      @RequestBody @Valid AuthorizationService.PermissionChange input) {
    service.replace(actor, id.toString(), Resource.KNOWLEDGE_BASE, input);
  }

  @PutMapping("/documents/{id}/permissions")
  public void docReplace(
      @RequestAttribute Actor actor,
      @PathVariable UUID id,
      @RequestBody @Valid AuthorizationService.PermissionChange input) {
    service.replace(actor, id.toString(), Resource.DOCUMENT, input);
  }
}
