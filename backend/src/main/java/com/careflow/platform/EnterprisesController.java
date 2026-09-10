package com.careflow.platform;

import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class EnterprisesController {
  private final EnterpriseProvisioning service;

  public EnterprisesController(EnterpriseProvisioning service) {
    this.service = service;
  }

  @PostMapping("/api/v1/enterprises")
  public Map<String, String> create(
      @RequestHeader("X-Bootstrap-Token") String token,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody @Valid EnterpriseProvisioning.ProvisionEnterprise input) {
    return service.create(token, key, input);
  }
}
