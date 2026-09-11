package com.careflow.platform;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/demo")
public class DemoAuthController {
  private final DemoAuthService service;

  public DemoAuthController(DemoAuthService service) {
    this.service = service;
  }

  @GetMapping("/status")
  public Map<String, Boolean> status() {
    return Map.of("enabled", service.enabled());
  }

  @PostMapping("/login")
  public Map<String, Object> login() {
    return service.login();
  }
}
