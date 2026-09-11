package com.careflow.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sso")
public class SsoController {
  public record Exchange(@NotBlank String token) {}

  private final SsoService service;

  public SsoController(SsoService service) {
    this.service = service;
  }

  @PostMapping("/exchange")
  public Object exchange(@RequestBody @Valid Exchange body) {
    return service.exchange(body.token());
  }
}
