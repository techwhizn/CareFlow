package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/applications/{id}/credentials")
public class ApplicationCredentialsController {
  private final ApplicationCredentialService service;

  public ApplicationCredentialsController(ApplicationCredentialService service) {
    this.service = service;
  }

  @PostMapping
  public Object create(
      @RequestAttribute Actor actor,
      @PathVariable UUID id,
      @RequestBody(required = false) @Valid ApplicationCredentialService.Options options) {
    return service.create(actor, id.toString(), options);
  }

  @GetMapping
  public Object list(@RequestAttribute Actor actor, @PathVariable UUID id) {
    return service.list(actor, id.toString());
  }

  @DeleteMapping("/{credential}")
  public void revoke(
      @RequestAttribute Actor actor, @PathVariable UUID id, @PathVariable UUID credential) {
    service.revoke(actor, id.toString(), credential.toString());
  }
}
