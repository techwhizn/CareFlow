package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/evaluation-datasets")
public class EvaluationDatasetController {
  private final EvaluationDatasets service;

  public EvaluationDatasetController(EvaluationDatasets service) {
    this.service = service;
  }

  @GetMapping
  public Object list(@RequestAttribute Actor actor) {
    return service.list(actor);
  }

  @PostMapping
  public Object create(
      @RequestAttribute Actor actor, @Valid @RequestBody EvaluationDatasets.Create input) {
    return service.create(actor, input);
  }

  @GetMapping("/{id}")
  public Object detail(@RequestAttribute Actor actor, @PathVariable UUID id) {
    return service.detail(actor, id.toString());
  }

  @PostMapping("/{id}/versions")
  public Object save(
      @RequestAttribute Actor actor,
      @PathVariable UUID id,
      @Valid @RequestBody EvaluationDatasets.Version input) {
    return service.save(actor, id.toString(), input);
  }

  @GetMapping("/{id}/versions/{version}")
  public Object version(
      @RequestAttribute Actor actor, @PathVariable UUID id, @PathVariable UUID version) {
    return service.version(actor, id.toString(), version.toString());
  }

  @PostMapping("/{id}/versions/{version}/reviews/{caseId}")
  public Object review(
      @RequestAttribute Actor actor,
      @PathVariable UUID id,
      @PathVariable UUID version,
      @PathVariable String caseId,
      @Valid @RequestBody EvaluationDatasets.Review input) {
    return service.review(actor, id.toString(), version.toString(), caseId, input);
  }
}
