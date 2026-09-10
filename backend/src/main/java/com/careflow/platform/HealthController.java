package com.careflow.platform;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Cheap process/database probes; no model invocation or sensitive dependency details. */
@RestController
public class HealthController {
  private final Db db;

  public HealthController(Db db) {
    this.db = db;
  }

  @GetMapping("/health/live")
  public Object live() {
    return Map.of("status", "UP");
  }

  @GetMapping({"/health/ready", "/actuator/health"})
  public ResponseEntity<?> ready() {
    try {
      db.one("SELECT 1 AS ready");
      return ResponseEntity.ok(Map.of("status", "UP", "scope", "DATABASE"));
    } catch (Exception unavailable) {
      return ResponseEntity.status(503).body(Map.of("status", "DOWN", "scope", "DATABASE"));
    }
  }
}
