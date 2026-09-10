package com.careflow.platform;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/jobs/{id}/ocr-pages")
public class OcrAccountingController {
  private final OcrAccountingService service;

  public OcrAccountingController(OcrAccountingService service) {
    this.service = service;
  }

  @PutMapping("/{callId}")
  public void record(
      @PathVariable String id,
      @PathVariable String callId,
      @RequestHeader("X-Lease-Token") String lease,
      @RequestBody @Valid OcrAccountingService.Report body) {
    service.record(id, lease, callId, body);
  }
}
