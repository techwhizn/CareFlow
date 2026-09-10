package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/answers/{answer}/citations")
public class AnswerCitationsController {
  private final AnswerCitationService service;

  public AnswerCitationsController(AnswerCitationService service) {
    this.service = service;
  }

  @GetMapping("/{citation}")
  public Object source(
      @RequestAttribute Actor actor, @PathVariable UUID answer, @PathVariable UUID citation) {
    return service.source(actor, answer.toString(), citation.toString());
  }
}
