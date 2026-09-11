package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/quality")
public class QualityFeedbackController {
  private final QualityFeedbackService service;

  public QualityFeedbackController(QualityFeedbackService service) {
    this.service = service;
  }

  @GetMapping("/feedback-summary")
  public Object summary(@RequestAttribute Actor actor) {
    return service.summary(actor);
  }
}
