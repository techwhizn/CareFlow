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
  private final com.fasterxml.jackson.databind.ObjectMapper mapper;

  public InternalController(
      Db db, Tasks tasks, BlobStore blobs, com.fasterxml.jackson.databind.ObjectMapper mapper) {
    this.db = db;
    this.tasks = tasks;
    this.blobs = blobs;
    this.mapper = mapper;
  }

  @PostMapping("/{id}/claim")
  public WorkerProtocolV1.TaskClaim claim(@PathVariable String id) {
    return mapper.convertValue(tasks.claim(id), WorkerProtocolV1.TaskClaim.class);
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
  public List<WorkerProtocolV1.IndexChunk> chunks(
      @PathVariable String id, @RequestHeader("X-Lease-Token") String lease) {
    var j = tasks.validate(id, lease);
    return db
        .list(
            "SELECT * FROM chunks WHERE tenant_id=? AND version_id=? AND enabled=TRUE ORDER BY ordinal_no",
            str(j, "tenant_id"),
            str(j, "version_id"))
        .stream()
        .map(c -> new WorkerProtocolV1.IndexChunk(str(c, "id"), str(c, "content")))
        .toList();
  }

  @PostMapping("/{id}/complete")
  public void complete(
      @PathVariable String id,
      @RequestHeader("X-Lease-Token") String lease,
      @RequestBody @jakarta.validation.Valid WorkerProtocolV1.TaskCompletion body) {
    Map<String, Object> payload =
        mapper.convertValue(
            body, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    tasks.complete(id, lease, payload);
  }

  @PutMapping("/{id}/model-calls/{callId}")
  public void modelCall(
      @PathVariable String id,
      @PathVariable String callId,
      @RequestHeader("X-Lease-Token") String lease,
      @RequestBody @jakarta.validation.Valid IndexAccountingService.Call body) {
    tasks.recordModelCall(id, lease, callId, body);
  }

  @PostMapping("/{id}/failed")
  public void failed(
      @PathVariable String id,
      @RequestHeader("X-Lease-Token") String lease,
      @RequestBody @jakarta.validation.Valid WorkerProtocolV1.TaskFailure body) {
    tasks.failed(id, lease, body.code(), body.retryable());
  }
}
