package com.careflow.platform;

import static com.careflow.platform.Db.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class CleanupRepository {
  private final Db db;
  private final TransactionTemplate tx;

  public CleanupRepository(Db db, TransactionTemplate tx) {
    this.db = db;
    this.tx = tx;
  }

  public String enqueue(String tenant, String type, String resource, String actor) {
    return enqueue(
        tenant,
        type,
        resource,
        actor,
        Timestamp.from(Instant.now().plusSeconds(120)),
        Timestamp.from(Instant.now().plusSeconds(7 * 86400)));
  }

  public String enqueue(
      String tenant,
      String type,
      String resource,
      String actor,
      Timestamp due,
      Timestamp deadline) {
    if (!Set.of("KNOWLEDGE_BASE", "DOCUMENT", "DOCUMENT_VERSION", "INDEX_GENERATION")
        .contains(type)) throw new IllegalArgumentException();
    var existing =
        db.list(
            "SELECT id FROM cleanup_requests WHERE tenant_id=? AND resource_type=? AND resource_id=?",
            tenant,
            type,
            resource);
    if (!existing.isEmpty()) return str(existing.getFirst(), "id");
    String id = id();
    db.exec(
        "INSERT INTO cleanup_requests(id,tenant_id,resource_type,resource_id,requested_by,not_before,deadline_at) VALUES(?,?,?,?,?,?,?)",
        id,
        tenant,
        type,
        resource,
        actor,
        due,
        deadline);
    return id;
  }

  public List<Map<String, Object>> due() {
    return db.list(
        "SELECT id FROM cleanup_requests WHERE state IN ('PENDING','RETRY','RUNNING') AND not_before<=CURRENT_TIMESTAMP AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP) ORDER BY not_before,created_at LIMIT 20");
  }

  public Map<String, Object> claim(String id) {
    return tx.execute(
        status -> {
          var owner = db.one("SELECT tenant_id FROM cleanup_requests WHERE id=?", id);
          db.one("SELECT id FROM tenants WHERE id=? FOR UPDATE", str(owner, "tenant_id"));
          var rows =
              db.list(
                  "SELECT * FROM cleanup_requests WHERE id=? AND state IN ('PENDING','RETRY','RUNNING') AND not_before<=CURRENT_TIMESTAMP AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP) FOR UPDATE",
                  id);
          if (rows.isEmpty()) return null;
          var job = rows.getFirst();
          Instant legacyGrace = ((Timestamp) job.get("created_at")).toInstant().plusSeconds(120);
          if (job.get("deadline_at") == null && legacyGrace.isAfter(Instant.now())) {
            db.exec(
                "UPDATE cleanup_requests SET not_before=?,deadline_at=? WHERE id=?",
                Timestamp.from(legacyGrace),
                Timestamp.from(legacyGrace.plusSeconds(7 * 86400 - 120)),
                id);
            return null;
          }
          String lease = id();
          db.exec(
              "UPDATE cleanup_requests SET state='RUNNING',attempts=attempts+1,lease_token=?,lease_until=?,deadline_at=COALESCE(deadline_at,?) WHERE id=?",
              lease,
              Timestamp.from(Instant.now().plusSeconds(300)),
              Timestamp.from(
                  ((Timestamp) job.get("created_at")).toInstant().plusSeconds(7 * 86400)),
              id);
          job = db.one("SELECT * FROM cleanup_requests WHERE id=?", id);
          try {
            requireScope(job);
          } catch (ApiException | IllegalArgumentException unavailable) {
            db.exec(
                "UPDATE cleanup_requests SET state='BLOCKED',error_code='RESOURCE_SCOPE_NOT_PURGEABLE',lease_token=NULL,lease_until=NULL WHERE id=?",
                id);
            return null;
          }
          return job;
        });
  }

  public void requireScope(Map<String, Object> job) {
    String tenant = str(job, "tenant_id"), resource = str(job, "resource_id");
    switch (str(job, "resource_type")) {
      case "KNOWLEDGE_BASE" ->
          db.one(
              "SELECT id FROM knowledge_bases WHERE tenant_id=? AND id=? AND status='DELETED'",
              tenant,
              resource);
      case "DOCUMENT" ->
          db.one(
              "SELECT d.id FROM documents d JOIN knowledge_bases k ON k.id=d.kb_id AND k.tenant_id=d.tenant_id WHERE d.tenant_id=? AND d.id=? AND (d.status='DELETED' OR k.status='DELETED')",
              tenant,
              resource);
      case "DOCUMENT_VERSION" ->
          db.one(
              "SELECT v.id FROM document_versions v JOIN documents d ON d.id=v.document_id AND d.tenant_id=v.tenant_id JOIN knowledge_bases k ON k.id=d.kb_id AND k.tenant_id=d.tenant_id WHERE v.tenant_id=? AND v.id=? AND (d.status='DELETED' OR k.status='DELETED')",
              tenant,
              resource);
      case "INDEX_GENERATION" ->
          db.one(
              "SELECT g.id FROM index_generations g JOIN document_versions v ON v.id=g.version_id AND v.tenant_id=g.tenant_id WHERE g.tenant_id=? AND g.id=? AND g.state IN ('RETIRED','FAILED','CANCELLED','PURGED') AND (v.active_index_generation IS NULL OR v.active_index_generation<>g.id)",
              tenant,
              resource);
      default -> throw new IllegalArgumentException();
    }
  }

  public void fenced(Map<String, Object> job, Runnable operation) {
    tx.executeWithoutResult(
        status -> {
          db.one("SELECT id FROM tenants WHERE id=? FOR UPDATE", str(job, "tenant_id"));
          db.one(
              "SELECT id FROM cleanup_requests WHERE id=? AND state='RUNNING' AND lease_token=? AND lease_until>CURRENT_TIMESTAMP FOR UPDATE",
              str(job, "id"),
              str(job, "lease_token"));
          requireScope(job);
          operation.run();
        });
  }

  public void next(
      Map<String, Object> job, String phase, String payload, String compactions, int waitSeconds) {
    fenced(
        job,
        () ->
            db.exec(
                "UPDATE cleanup_requests SET state='PENDING',phase=?,payload_json=?,compactions_json=?,lease_token=NULL,lease_until=NULL,error_code=NULL,not_before=? WHERE id=?",
                phase,
                payload,
                compactions,
                Timestamp.from(Instant.now().plusSeconds(waitSeconds)),
                str(job, "id")));
  }

  public void complete(Map<String, Object> job, Runnable purge) {
    fenced(
        job,
        () -> {
          purge.run();
          db.exec(
              "UPDATE cleanup_requests SET state='DONE',phase='DONE',payload_json=NULL,compactions_json=NULL,lease_token=NULL,lease_until=NULL,error_code=NULL,completed_at=CURRENT_TIMESTAMP WHERE id=?",
              str(job, "id"));
          db.exec(
              "INSERT INTO audit_events(id,tenant_id,actor_id,action,resource_id,details) VALUES(?,?,'SYSTEM','PHYSICAL_CLEANUP_COMPLETE',?,?)",
              id(),
              str(job, "tenant_id"),
              str(job, "resource_id"),
              "request=" + str(job, "id"));
        });
  }

  public void failed(Map<String, Object> job) {
    tx.executeWithoutResult(
        status -> {
          int updated =
              db.exec(
                  "UPDATE cleanup_requests SET state='RETRY',failures=failures+1,error_code='PHYSICAL_CLEANUP_UNAVAILABLE',lease_token=NULL,lease_until=NULL,not_before=? WHERE id=? AND lease_token=? AND state='RUNNING'",
                  Timestamp.from(
                      Instant.now()
                          .plusSeconds(Math.min(3600, 30L << Math.min(7, num(job, "failures"))))),
                  str(job, "id"),
                  str(job, "lease_token"));
          if (updated == 1)
            db.exec(
                "INSERT INTO audit_events(id,tenant_id,actor_id,action,resource_id,details) VALUES(?,?,'SYSTEM','PHYSICAL_CLEANUP_RETRY',?,?)",
                id(),
                str(job, "tenant_id"),
                str(job, "resource_id"),
                "phase=" + str(job, "phase") + ", request=" + str(job, "id"));
        });
  }

  public void seed() {
    for (var tenant : db.list("SELECT id FROM tenants ORDER BY id"))
      tx.executeWithoutResult(
          status -> {
            String t = str(tenant, "id");
            db.one("SELECT id FROM tenants WHERE id=? FOR UPDATE", t);
            for (var kb :
                db.list(
                    "SELECT id FROM knowledge_bases WHERE tenant_id=? AND status='DELETED' AND purged_at IS NULL",
                    t)) enqueue(t, "KNOWLEDGE_BASE", str(kb, "id"), "SYSTEM");
            for (var doc :
                db.list(
                    "SELECT id FROM documents WHERE tenant_id=? AND status='DELETED' AND purged_at IS NULL",
                    t)) enqueue(t, "DOCUMENT", str(doc, "id"), "SYSTEM");
            for (var generation :
                db.list(
                    "SELECT id,completed_at FROM index_generations WHERE tenant_id=? AND state IN ('RETIRED','FAILED','CANCELLED') AND completed_at<? ORDER BY completed_at LIMIT 100",
                    t,
                    Timestamp.from(Instant.now().minusSeconds(86400))))
              enqueue(
                  t,
                  "INDEX_GENERATION",
                  str(generation, "id"),
                  "SYSTEM",
                  Timestamp.from(Instant.now()),
                  Timestamp.from(
                      ((Timestamp) generation.get("completed_at"))
                          .toInstant()
                          .plusSeconds(7 * 86400)));
          });
  }
}
