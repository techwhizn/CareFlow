package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import com.careflow.platform.RetrievalService.Scope;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class EvidenceAuthorization {
  private final Db db;
  private final Identity auth;

  public EvidenceAuthorization(Db db, Identity auth) {
    this.db = db;
    this.auth = auth;
  }

  public void check(Actor actor, Map<String, Object> c, Scope scope) {
    var d = auth.document(actor, str(c, "document_id"), "read");
    var k = auth.kb(actor, str(d, "kb_id"), "read");
    if (!str(k, "status").equals("ACTIVE") || !str(d, "status").equals("ACTIVE"))
      throw ApiException.hidden();
    var now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
    db.one(
        "SELECT id FROM documents WHERE tenant_id=? AND id=? AND (valid_from IS NULL OR valid_from<=?) AND (valid_until IS NULL OR valid_until>?)",
        actor.tenant(),
        str(d, "id"),
        now,
        now);
    db.one(
        "SELECT id FROM document_versions WHERE tenant_id=? AND id=? AND document_id=? AND state<>'PURGED' AND ever_published=TRUE AND (valid_from IS NULL OR valid_from<=CURRENT_TIMESTAMP) AND (valid_until IS NULL OR valid_until>CURRENT_TIMESTAMP)",
        actor.tenant(),
        str(c, "version_id"),
        str(d, "id"));
    if (!str(c, "id").isBlank())
      db.one(
          "SELECT id FROM chunks WHERE tenant_id=? AND version_id=? AND id=? AND enabled=TRUE",
          actor.tenant(),
          str(c, "version_id"),
          str(c, "id"));
    if (c.get("covered_chunk_ids") instanceof List<?> covered) {
      for (Object chunk : covered)
        db.one(
            "SELECT id FROM chunks WHERE tenant_id=? AND version_id=? AND id=? AND enabled=TRUE",
            actor.tenant(),
            str(c, "version_id"),
            chunk);
    }
    if (!scope.application().isBlank()) {
      db.one(
          "SELECT b.kb_id FROM application_bindings b JOIN applications a ON a.id=b.application_id WHERE b.tenant_id=? AND b.application_id=? AND b.kb_id=? AND a.published=TRUE",
          actor.tenant(),
          scope.application(),
          str(d, "kb_id"));
    }
  }
}
