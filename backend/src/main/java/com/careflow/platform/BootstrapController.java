package com.careflow.platform;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class BootstrapController {
  private final BootstrapService service;

  public BootstrapController(BootstrapService service) {
    this.service = service;
  }

  @PostMapping("/bootstrap")
  public Map<String, Object> bootstrap(
      @RequestHeader("X-Bootstrap-Token") String token,
      @RequestBody @Valid BootstrapService.Named input) {
    return service.bootstrap(token, input);
  }
}
