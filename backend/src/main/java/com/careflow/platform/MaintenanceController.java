package com.careflow.platform;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/maintenance")
public class MaintenanceController {
  private final ModelKeyRotationService rotation;

  public MaintenanceController(ModelKeyRotationService rotation) {
    this.rotation = rotation;
  }

  @PostMapping("/model-key-rotation")
  public Map<String, Object> rotateModelKey() {
    return rotation.rotate();
  }
}
