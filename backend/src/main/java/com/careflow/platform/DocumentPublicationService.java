package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentPublicationService {
  private final Db db;
  private final Identity auth;

  public DocumentPublicationService(Db db, Identity auth) {
    this.db = db;
    this.auth = auth;
  }

  public record Publish(
      @NotBlank String version_id, @Min(0) long revision, @NotNull @Min(0) Long version_revision) {}

  @Transactional(readOnly = true)
  public List<Map<String, Object>> history(Actor actor, String id) {
    auth.document(actor, id, "read");
    return db
        .list(
            "SELECT * FROM publications WHERE tenant_id=? AND document_id=? ORDER BY created_at DESC,document_revision DESC,id DESC LIMIT 100",
            actor.tenant(),
            id)
        .stream()
        .filter(
            row -> {
              try {
                auth.version(actor, str(row, "version_id"), "read");
                return true;
              } catch (ApiException denied) {
                return false;
              }
            })
        .toList();
  }

  @Transactional
  public Object publish(Actor actor, String id, Publish body) {
    auth.lock(actor);
    var document = auth.document(actor, id, "publish");
    var v = auth.version(actor, body.version_id(), "publish");
    if (!str(v, "document_id").equals(id)) throw ApiException.hidden();
    if (body.version_revision() == null || num(v, "revision") != body.version_revision())
      throw ApiException.conflict();
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
        "INSERT INTO publications(id,tenant_id,document_id,version_id,actor_id,document_revision,version_revision,previous_version) VALUES(?,?,?,?,?,?,?,?)",
        pub,
        actor.tenant(),
        id,
        body.version_id(),
        actor.subject(),
        body.revision() + 1,
        body.version_revision(),
        document.get("published_version"));
    auth.audit(actor, "DOCUMENT_PUBLISH", id, body.version_id());
    return Map.of("publication_id", pub);
  }

  @Transactional
  public void delete(Actor actor, String id, long revision) {
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
}
