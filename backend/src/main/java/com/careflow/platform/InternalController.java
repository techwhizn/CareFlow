package com.careflow.platform;

import static com.careflow.platform.Db.*;

import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/jobs")
public class InternalController {
  private final Db db;
  private final Tasks tasks;
  private final BlobStore blobs;

  public InternalController(Db db, Tasks tasks, BlobStore blobs) {
    this.db = db;
    this.tasks = tasks;
    this.blobs = blobs;
  }

  @PostMapping("/{id}/claim")
  public Object claim(@PathVariable String id) {
    return tasks.claim(id);
  }

  @PostMapping("/{id}/heartbeat")
  public void heartbeat(@PathVariable String id, @RequestHeader("X-Lease-Token") String lease) {
    tasks.heartbeat(id, lease);
  }

  public record Checkpoint(@jakarta.validation.constraints.NotBlank String stage) {}

  @PostMapping("/{id}/checkpoint")
  public void checkpoint(
      @PathVariable String id,
      @RequestHeader("X-Lease-Token") String lease,
      @RequestBody @jakarta.validation.Valid Checkpoint body) {
    tasks.checkpoint(id, lease, body.stage());
  }

  @GetMapping("/{id}/source")
  @Transactional
  public byte[] source(@PathVariable String id, @RequestHeader("X-Lease-Token") String lease) {
    var j = tasks.validate(id, lease);
    var v = db.one("SELECT object_key FROM document_versions WHERE id=?", str(j, "version_id"));
    return blobs.get(str(v, "object_key"));
  }

  @GetMapping("/{id}/chunks")
  @Transactional
  public Object chunks(@PathVariable String id, @RequestHeader("X-Lease-Token") String lease) {
    var j = tasks.validate(id, lease);
    return db.list(
        "SELECT * FROM chunks WHERE tenant_id=? AND version_id=? AND enabled=TRUE ORDER BY ordinal_no",
        str(j, "tenant_id"),
        str(j, "version_id"));
  }

  @PostMapping("/{id}/complete")
  public void complete(
      @PathVariable String id,
      @RequestHeader("X-Lease-Token") String lease,
      @RequestBody Map<String, Object> body) {
    tasks.complete(id, lease, body);
  }

  @PostMapping("/{id}/failed")
  public void failed(
      @PathVariable String id,
      @RequestHeader("X-Lease-Token") String lease,
      @RequestBody Map<String, Object> body) {
    tasks.failed(id, lease, str(body, "code"), Boolean.TRUE.equals(body.get("retryable")));
  }
}
