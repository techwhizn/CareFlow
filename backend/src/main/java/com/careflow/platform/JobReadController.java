package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class JobReadController {
  private final JobReadService service;

  public JobReadController(JobReadService service) {
    this.service = service;
  }

  @GetMapping("/jobs")
  public Object jobs(@RequestAttribute Actor actor) {
    return service.jobs(actor);
  }

  @GetMapping("/jobs/{id}")
  public Object job(@RequestAttribute Actor actor, @PathVariable String id) {
    return service.job(actor, id);
  }
}
