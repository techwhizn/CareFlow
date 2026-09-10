package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentDraftService {
  private final Db db;
  private final Identity auth;
  private final WorkerClient worker;

  public DocumentDraftService(Db db, Identity auth, WorkerClient worker) {
    this.db = db;
    this.auth = auth;
    this.worker = worker;
  }

  public record Edit(
      @NotBlank @Size(max = 2000) String content,
      boolean enabled,
      long revision,
      @NotBlank @Size(max = 1000) String reason) {}

  @Transactional
  public Object edit(Actor actor, String id, Edit body) {
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

  @Transactional
  public Object draft(Actor actor, String id) {
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

  @Transactional
  public Object index(Actor actor, String id, String key) {
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
}
