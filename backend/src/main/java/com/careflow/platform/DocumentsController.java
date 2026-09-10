package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class DocumentsController {
  private final Db db;
  private final Identity auth;
  private final BlobStore blobs;
  private final WorkerClient worker;
  private final DocumentUploadService uploads;
  private final Tasks tasks;

  public DocumentsController(
      Db db,
      Identity auth,
      BlobStore blobs,
      WorkerClient worker,
      DocumentUploadService uploads,
      Tasks tasks) {
    this.db = db;
    this.auth = auth;
    this.blobs = blobs;
    this.worker = worker;
    this.uploads = uploads;
    this.tasks = tasks;
  }

  public record Publish(@NotBlank String version_id, long revision) {}

  public record Edit(
      @NotBlank @Size(max = 2000) String content,
      boolean enabled,
      long revision,
      @NotBlank @Size(max = 1000) String reason) {}

  @GetMapping("/knowledge-bases/{id}/documents")
  public Object documents(
      @RequestAttribute Actor actor,
      @PathVariable String id,
      @RequestParam(defaultValue = "0") @Min(0) int page) {
    auth.kb(actor, id, "read");
    return db
        .list(
            "SELECT * FROM documents WHERE tenant_id=? AND kb_id=? AND status<>'DELETED' ORDER BY created_at DESC LIMIT 50 OFFSET ?",
            actor.tenant(),
            id,
            Math.max(0, page) * 50)
        .stream()
        .filter(
            d -> {
              try {
                auth.document(actor, str(d, "id"), "read");
                return true;
              } catch (ApiException e) {
                return false;
              }
            })
        .toList();
  }

  @PostMapping("/knowledge-bases/{id}/documents")
  public Object upload(
      @RequestAttribute Actor actor,
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String requestKey,
      @RequestHeader("Authorization") String authorization,
      @RequestParam MultipartFile file)
      throws Exception {
    auth.kb(actor, id, "edit");
    return uploads.upload(actor, authorization, id, null, requestKey, file);
  }

  @PostMapping("/documents/{id}/versions")
  public Object replace(
      @RequestAttribute Actor actor,
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String requestKey,
      @RequestHeader("Authorization") String authorization,
      @RequestParam MultipartFile file)
      throws Exception {
    var d = auth.document(actor, id, "edit");
    return uploads.upload(actor, authorization, str(d, "kb_id"), id, requestKey, file);
  }

  @GetMapping("/documents/{id}/versions")
  public Object versions(@RequestAttribute Actor actor, @PathVariable String id) {
    auth.document(actor, id, "read");
    return db
        .list(
            "SELECT id,document_id,filename,state,revision,ever_published,model_identity,created_at FROM document_versions WHERE tenant_id=? AND document_id=? ORDER BY created_at DESC",
            actor.tenant(),
            id)
        .stream()
        .filter(
            v -> {
              try {
                auth.version(actor, str(v, "id"), "read");
                return true;
              } catch (ApiException e) {
                if (e.status != 404) throw e;
                return false;
              }
            })
        .toList();
  }

  @GetMapping("/document-versions/{id}/chunks")
  public Object chunks(
      @RequestAttribute Actor actor,
      @PathVariable String id,
      @RequestParam(defaultValue = "0") int page) {
    auth.version(actor, id, "read");
    return db.list(
        "SELECT * FROM chunks WHERE tenant_id=? AND version_id=? ORDER BY ordinal_no LIMIT 100 OFFSET ?",
        actor.tenant(),
        id,
        Math.max(0, page) * 100);
  }

  @GetMapping("/document-versions/{id}/source")
  public ResponseEntity<byte[]> download(@RequestAttribute Actor actor, @PathVariable String id) {
    var v = auth.version(actor, id, "download");
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(str(v, "filename"), StandardCharsets.UTF_8)
                .build()
                .toString())
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(blobs.get(str(v, "object_key")));
  }

  @PutMapping("/chunks/{id}")
  @Transactional
  public Object edit(
      @RequestAttribute Actor actor, @PathVariable String id, @RequestBody @Valid Edit body) {
    auth.lock(actor);
    var c = db.one("SELECT * FROM chunks WHERE tenant_id=? AND id=?", actor.tenant(), id);
    var v = auth.version(actor, str(c, "version_id"), "edit");
    if (bool(v, "ever_published"))
      throw new ApiException(409, "IMMUTABLE_PUBLICATION", "已发布版本不可修改，请先复制为草稿");
    if (!Set.of("PARSED", "READY", "FAILED").contains(str(v, "state")))
      throw new ApiException(409, "PROCESSING", "任务执行中不可编辑");
    var tokenized = worker.call("/internal/v1/tokenize", Map.of("text", body.content()));
    long tokens = num(tokenized, "token_count");
    if (tokens > 600) throw new ApiException(400, "CHUNK_TOO_LONG", "切片超过 600 Token，请缩短后保存");
    if (db.exec(
            "UPDATE chunks SET content=?,token_count=?,enabled=?,revision=revision+1 WHERE tenant_id=? AND id=? AND revision=?",
            body.content(),
            tokens,
            body.enabled(),
            actor.tenant(),
            id,
            body.revision())
        != 1) throw ApiException.conflict();
    db.exec(
        "UPDATE document_versions SET state='PARSED',revision=revision+1 WHERE tenant_id=? AND id=?",
        actor.tenant(),
        str(v, "id"));
    db.exec(
        "INSERT INTO chunk_revisions(id,tenant_id,chunk_id,revision,previous_content,actor_id,reason) VALUES(?,?,?,?,?,?,?)",
        id(),
        actor.tenant(),
        id,
        num(c, "revision"),
        str(c, "content"),
        actor.subject(),
        body.reason());
    auth.audit(actor, "CHUNK_EDIT", id, "reason=" + body.reason());
    return db.one("SELECT * FROM chunks WHERE tenant_id=? AND id=?", actor.tenant(), id);
  }

  @PostMapping("/document-versions/{id}/draft")
  @Transactional
  public Object draft(@RequestAttribute Actor actor, @PathVariable String id) {
    auth.lock(actor);
    var v = auth.version(actor, id, "edit");
    String next = id();
    db.exec(
        "INSERT INTO document_versions(id,tenant_id,document_id,object_key,filename,digest,state,size_bytes) VALUES(?,?,?,?,?,?,'PARSED',?)",
        next,
        actor.tenant(),
        str(v, "document_id"),
        str(v, "object_key"),
        str(v, "filename"),
        str(v, "digest"),
        v.get("size_bytes"));
    for (var c :
        db.list("SELECT * FROM chunks WHERE tenant_id=? AND version_id=?", actor.tenant(), id))
      db.exec(
          "INSERT INTO chunks(id,tenant_id,version_id,ordinal_no,source_text,content,location,token_count,enabled) VALUES(?,?,?,?,?,?,?,?,?)",
          id(),
          actor.tenant(),
          next,
          c.get("ordinal_no"),
          c.get("source_text"),
          c.get("content"),
          c.get("location"),
          c.get("token_count"),
          c.get("enabled"));
    auth.audit(actor, "DRAFT_CREATE", next, id);
    return Map.of("id", next);
  }

  @PostMapping("/document-versions/{id}/index")
  @Transactional
  public Object index(
      @RequestAttribute Actor actor,
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key) {
    auth.lock(actor);
    var v = auth.version(actor, id, "edit");
    if (bool(v, "ever_published"))
      throw new ApiException(409, "IMMUTABLE_PUBLICATION", "已发布版本不可重新索引");
    if (!Set.of("PARSED", "FAILED").contains(str(v, "state")))
      throw new ApiException(409, "NOT_READY", "请先完成解析或修改切片");
    if (key.isBlank() || key.length() > 100) throw new IllegalArgumentException();
    String job = id();
    db.exec(
        "INSERT INTO jobs(id,tenant_id,version_id,kind,request_key) VALUES(?,?,?,'INDEX',?)",
        job,
        actor.tenant(),
        id,
        key);
    db.exec("INSERT INTO outbox(id,job_id) VALUES(?,?)", id(), job);
    db.exec(
        "UPDATE document_versions SET state='QUEUED' WHERE tenant_id=? AND id=?",
        actor.tenant(),
        id);
    return Map.of("job_id", job);
  }

  @PostMapping("/documents/{id}/publications")
  @Transactional
  public Object publish(
      @RequestAttribute Actor actor, @PathVariable String id, @RequestBody @Valid Publish body) {
    auth.lock(actor);
    auth.document(actor, id, "publish");
    var v = auth.version(actor, body.version_id(), "publish");
    if (!str(v, "document_id").equals(id)) throw ApiException.hidden();
    if (!str(v, "state").equals("READY")) throw new ApiException(409, "NOT_READY", "真实索引校验完成后才能发布");
    if (db.exec(
            "UPDATE documents SET published_version=?,revision=revision+1 WHERE tenant_id=? AND id=? AND revision=?",
            body.version_id(),
            actor.tenant(),
            id,
            body.revision())
        != 1) throw ApiException.conflict();
    db.exec(
        "UPDATE document_versions SET ever_published=TRUE WHERE tenant_id=? AND id=?",
        actor.tenant(),
        body.version_id());
    String pub = id();
    db.exec(
        "INSERT INTO publications(id,tenant_id,document_id,version_id,actor_id) VALUES(?,?,?,?,?)",
        pub,
        actor.tenant(),
        id,
        body.version_id(),
        actor.subject());
    auth.audit(actor, "DOCUMENT_PUBLISH", id, body.version_id());
    return Map.of("publication_id", pub);
  }

  @DeleteMapping("/documents/{id}")
  @Transactional
  public void delete(
      @RequestAttribute Actor actor, @PathVariable String id, @RequestParam long revision) {
    auth.lock(actor);
    auth.document(actor, id, "manage");
    if (db.exec(
            "UPDATE documents SET status='DELETED',published_version=NULL,revision=revision+1 WHERE tenant_id=? AND id=? AND revision=?",
            actor.tenant(),
            id,
            revision)
        != 1) throw ApiException.conflict();
    db.exec(
        "UPDATE jobs SET state='CANCELLED',lease_token=NULL WHERE tenant_id=? AND version_id IN (SELECT id FROM document_versions WHERE document_id=?)",
        actor.tenant(),
        id);
    db.exec("UPDATE tenants SET revision=revision+1 WHERE id=?", actor.tenant());
    auth.audit(actor, "DOCUMENT_DELETE", id, "立即阻断访问，物理清理待运行维护流程");
  }

  @GetMapping("/jobs")
  public Object jobs(@RequestAttribute Actor actor) {
    return db
        .list(
            "SELECT * FROM jobs WHERE tenant_id=? ORDER BY created_at DESC LIMIT 100",
            actor.tenant())
        .stream()
        .filter(
            j -> {
              try {
                auth.version(actor, str(j, "version_id"), "read");
                return true;
              } catch (ApiException e) {
                return false;
              }
            })
        .map(this::publicJob)
        .toList();
  }

  private Map<String, Object> publicJob(Map<String, Object> job) {
    var result = new LinkedHashMap<>(job);
    result.remove("lease_token");
    result.remove("request_key");
    result.remove("upload_fingerprint");
    return result;
  }

  @GetMapping("/jobs/{id}")
  public Object job(@RequestAttribute Actor actor, @PathVariable String id) {
    var j = db.one("SELECT * FROM jobs WHERE tenant_id=? AND id=?", actor.tenant(), id);
    auth.version(actor, str(j, "version_id"), "read");
    return publicJob(j);
  }

  @PostMapping("/jobs/{id}/cancel")
  public void cancel(@RequestAttribute Actor actor, @PathVariable String id) {
    tasks.cancel(actor, id);
  }
}
