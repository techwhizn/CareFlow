package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class RequestLogController {
  private final RequestLogService service;

  public RequestLogController(RequestLogService service) {
    this.service = service;
  }

  @GetMapping({"/usage/requests", "/applications/{app}/requests"})
  public Object list(
      @RequestAttribute Actor actor,
      @PathVariable(required = false) String app,
      @RequestParam(required = false) UUID before) {
    return service.list(actor, app, before);
  }

  @GetMapping({"/usage/requests/{request}", "/applications/{app}/requests/{request}"})
  public Object detail(
      @RequestAttribute Actor actor,
      @PathVariable(required = false) String app,
      @PathVariable UUID request) {
    return service.detail(actor, app, request);
  }
}
