package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/members")
public class MembersController {
  private final MembershipService service;

  public MembersController(MembershipService service) {
    this.service = service;
  }

  @GetMapping
  public Object list(@RequestAttribute Actor actor) {
    return service.list(actor);
  }

  @PostMapping
  public Object create(
      @RequestAttribute Actor actor, @Valid @RequestBody MembershipService.Create input) {
    return service.create(actor, input);
  }

  @PutMapping("/{id}")
  public Object change(
      @RequestAttribute Actor actor,
      @PathVariable UUID id,
      @Valid @RequestBody MembershipService.Change input) {
    return service.change(actor, id.toString(), input);
  }

  @DeleteMapping("/{id}")
  public void disable(@RequestAttribute Actor actor, @PathVariable UUID id) {
    service.disable(actor, id.toString());
  }

  @PutMapping("/{id}/external-identity")
  public Object bindExternalIdentity(
      @RequestAttribute Actor actor,
      @PathVariable UUID id,
      @Valid @RequestBody MembershipService.ExternalIdentity input) {
    return service.bindExternalIdentity(actor, id.toString(), input);
  }

  @PostMapping("/{id}/credentials")
  public Object issue(@RequestAttribute Actor actor, @PathVariable UUID id) {
    return service.issueCredential(actor, id.toString());
  }
}
