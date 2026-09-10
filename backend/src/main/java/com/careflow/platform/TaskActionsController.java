package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/jobs")
public class TaskActionsController {
  private final Tasks tasks;

  public TaskActionsController(Tasks tasks) {
    this.tasks = tasks;
  }

  @PostMapping("/{id}/cancel")
  public void cancel(@RequestAttribute Actor actor, @PathVariable String id) {
    tasks.cancel(actor, id);
  }
}
