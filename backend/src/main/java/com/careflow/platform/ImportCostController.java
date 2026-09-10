package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/knowledge-bases/{id}/import-estimate")
public class ImportCostController {
  private final ImportCostService service;

  public ImportCostController(ImportCostService service) {
    this.service = service;
  }

  @PostMapping(consumes = "multipart/form-data")
  public Object estimate(
      @RequestAttribute Actor actor,
      @RequestHeader("Authorization") String authorization,
      @PathVariable String id,
      @RequestPart("file") MultipartFile file)
      throws Exception {
    return service.estimate(actor, authorization, id, file);
  }
}
