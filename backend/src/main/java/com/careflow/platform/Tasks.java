package com.careflow.platform;

import static com.careflow.platform.Db.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class Tasks {
  private final Db db;
  private final RabbitTemplate rabbit;
  private final TransactionTemplate tx;
  private final boolean enabled;
  private final Identity auth;

  public Tasks(
      Db db,
      RabbitTemplate rabbit,
      TransactionTemplate tx,
      Identity auth,
      @Value("${careflow.scheduling}") boolean enabled) {
    this.db = db;
    this.rabbit = rabbit;
    this.tx = tx;
    this.auth = auth;
    this.enabled = enabled;
  }

  @Bean
  Queue processingQueue() {
    return new Queue("careflow.processing", true);
  }

  @Scheduled(fixedDelay = 5000)
  public void dispatch() {
    if (!enabled) return;
    try {
      recover();
      for (var row :
          db.list("SELECT * FROM outbox WHERE sent=FALSE ORDER BY created_at LIMIT 20")) {
        CorrelationData correlation = new CorrelationData(str(row, "id"));
        rabbit.convertAndSend("", "careflow.processing", str(row, "job_id"), correlation);
        var confirm = correlation.getFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
        if (confirm.isAck() && correlation.getReturned() == null)
          db.exec("UPDATE outbox SET sent=TRUE WHERE id=?", str(row, "id"));
      }
    } catch (Exception e) {
      /* Durable outbox remains unsent; next tick retries without logging payloads or credentials. */
    }
  }

  public void recover() {
    for (var candidate :
        db.list(
            "SELECT id,tenant_id FROM jobs WHERE state='RUNNING' AND lease_until<CURRENT_TIMESTAMP ORDER BY lease_until LIMIT 100")) {
      tx.executeWithoutResult(
          status -> {
            db.one("SELECT id FROM tenants WHERE id=? FOR UPDATE", str(candidate, "tenant_id"));
            var rows =
                db.list(
                    "SELECT * FROM jobs WHERE id=? AND state='RUNNING' AND lease_until<CURRENT_TIMESTAMP FOR UPDATE",
                    str(candidate, "id"));
            if (rows.isEmpty()) return;
            var job = rows.getFirst();
            boolean accessible =
                !db.list(
                        "SELECT d.id FROM documents d JOIN document_versions v ON v.document_id=d.id JOIN knowledge_bases k ON k.id=d.kb_id WHERE v.id=? AND d.status='ACTIVE' AND k.status='ACTIVE'",
                        str(job, "version_id"))
                    .isEmpty();
            String next =
                !accessible ? "CANCELLED" : num(job, "attempts") >= 3 ? "FAILED" : "QUEUED";
            db.exec(
                "UPDATE jobs SET state=?,lease_token=NULL,lease_until=NULL,error_code='LEASE_EXPIRED' WHERE id=?",
                next,
                str(job, "id"));
            db.exec(
                "UPDATE document_versions SET state=? WHERE id=?",
                next.equals("CANCELLED") ? "FAILED" : next,
                str(job, "version_id"));
            if (next.equals("QUEUED"))
              db.exec("INSERT INTO outbox(id,job_id) VALUES(?,?)", id(), str(job, "id"));
          });
    }
  }

  public void cancel(Identity.Actor actor, String id) {
    tx.executeWithoutResult(
        status -> {
          auth.lock(actor);
          var job =
              db.one(
                  "SELECT * FROM jobs WHERE tenant_id=? AND id=? FOR UPDATE", actor.tenant(), id);
          auth.version(actor, str(job, "version_id"), "edit");
          if (!Set.of("QUEUED", "RUNNING").contains(str(job, "state")))
            throw ApiException.conflict();
          db.exec(
              "UPDATE jobs SET state='CANCELLED',lease_token=NULL,lease_until=NULL,error_code='CANCELLED' WHERE id=?",
              id);
          db.exec("UPDATE document_versions SET state='FAILED' WHERE id=?", str(job, "version_id"));
          auth.audit(actor, "JOB_CANCEL", id, "");
        });
  }

  public void checkpoint(String id, String lease, String checkpoint) {
    tx.executeWithoutResult(
        status -> {
          var job = validate(id, lease);
          List<String> order =
              str(job, "kind").equals("PARSE")
                  ? List.of("QUEUED", "STARTED", "SOURCE_READY", "PARSED")
                  : List.of("QUEUED", "STARTED", "INDEXING", "INDEX_VERIFIED");
          if (!order.contains(checkpoint)
              || order.indexOf(checkpoint) < order.indexOf(str(job, "checkpoint")))
            throw new IllegalArgumentException();
          db.exec(
              "UPDATE jobs SET checkpoint=?,heartbeat_at=CURRENT_TIMESTAMP,lease_until=? WHERE id=?",
              checkpoint,
              Timestamp.from(Instant.now().plusSeconds(90)),
              id);
        });
  }

  public Map<String, Object> claim(String id) {
    return tx.execute(
        status -> {
          var tenantRow = db.one("SELECT tenant_id FROM jobs WHERE id=?", id);
          db.one("SELECT id FROM tenants WHERE id=? FOR UPDATE", str(tenantRow, "tenant_id"));
          var j = db.one("SELECT * FROM jobs WHERE id=? FOR UPDATE", id);
          if (!str(j, "state").equals("QUEUED")) throw ApiException.conflict();
          var v =
              db.one(
                  "SELECT v.* FROM document_versions v JOIN documents d ON d.id=v.document_id JOIN knowledge_bases k ON k.id=d.kb_id WHERE v.id=? AND d.status='ACTIVE' AND k.status='ACTIVE'",
                  str(j, "version_id"));
          String lease = id();
          db.exec(
              "UPDATE jobs SET state='RUNNING',attempts=attempts+1,checkpoint='STARTED',error_code=NULL,heartbeat_at=CURRENT_TIMESTAMP,lease_token=?,lease_until=? WHERE id=?",
              lease,
              Timestamp.from(Instant.now().plusSeconds(90)),
              id);
          db.exec(
              "UPDATE document_versions SET state=? WHERE id=?",
              str(j, "kind").equals("PARSE") ? "PARSING" : "INDEXING",
              str(j, "version_id"));
          return Map.of(
              "id",
              id,
              "lease_token",
              lease,
              "kind",
              str(j, "kind"),
              "tenant_id",
              str(j, "tenant_id"),
              "version_id",
              str(j, "version_id"),
              "filename",
              str(v, "filename"));
        });
  }

  public Map<String, Object> validate(String id, String lease) {
    var j =
        db.one(
            "SELECT * FROM jobs WHERE id=? AND lease_token=? AND state='RUNNING' AND lease_until>CURRENT_TIMESTAMP FOR UPDATE",
            id,
            lease);
    db.one(
        "SELECT d.id FROM documents d JOIN document_versions v ON v.document_id=d.id JOIN knowledge_bases k ON k.id=d.kb_id WHERE v.id=? AND d.status='ACTIVE' AND k.status='ACTIVE'",
        str(j, "version_id"));
    return j;
  }

  public void heartbeat(String id, String lease) {
    tx.executeWithoutResult(
        status -> {
          validate(id, lease);
          db.exec(
              "UPDATE jobs SET lease_until=?,heartbeat_at=CURRENT_TIMESTAMP WHERE id=?",
              Timestamp.from(Instant.now().plusSeconds(90)),
              id);
        });
  }

  public void complete(String id, String lease, Map<String, Object> body) {
    tx.executeWithoutResult(
        status -> {
          var tenantRow = db.one("SELECT tenant_id FROM jobs WHERE id=?", id);
          db.one("SELECT id FROM tenants WHERE id=? FOR UPDATE", str(tenantRow, "tenant_id"));
          var j = validate(id, lease);
          String tenant = str(j, "tenant_id"), version = str(j, "version_id");
          if (str(j, "kind").equals("PARSE")) {
            Object raw = body.get("chunks");
            if (!(raw instanceof List<?> chunks) || chunks.isEmpty() || chunks.size() > 50000)
              throw new IllegalArgumentException();
            db.exec("DELETE FROM chunks WHERE tenant_id=? AND version_id=?", tenant, version);
            int ordinal = 0;
            for (Object entry : chunks) {
              if (!(entry instanceof Map<?, ?> c)) throw new IllegalArgumentException();
              String content = Objects.toString(c.get("content"), "");
              if (content.isBlank() || content.length() > 10000)
                throw new IllegalArgumentException();
              db.exec(
                  "INSERT INTO chunks(id,tenant_id,version_id,ordinal_no,source_text,content,location,token_count) VALUES(?,?,?,?,?,?,?,?)",
                  id(),
                  tenant,
                  version,
                  ordinal++,
                  c.get("source_text"),
                  content,
                  c.get("location"),
                  c.get("token_count"));
            }
            db.exec("UPDATE document_versions SET state='PARSED' WHERE id=?", version);
          } else {
            if (!Boolean.TRUE.equals(body.get("verified")) || str(body, "model_identity").isBlank())
              throw new IllegalArgumentException();
            db.exec(
                "UPDATE document_versions SET state='READY',model_identity=? WHERE id=?",
                str(body, "model_identity"),
                version);
          }
          db.exec(
              "UPDATE jobs SET state='DONE',checkpoint='DONE',lease_token=NULL,lease_until=NULL,error_code=NULL WHERE id=?",
              id);
        });
  }

  public void failed(String id, String lease, String code, boolean retryable) {
    tx.executeWithoutResult(
        status -> {
          var tenant = db.one("SELECT tenant_id FROM jobs WHERE id=?", id);
          db.one("SELECT id FROM tenants WHERE id=? FOR UPDATE", str(tenant, "tenant_id"));
          var j = validate(id, lease);
          boolean terminal =
              Set.of(
                      "INVALID_FILE",
                      "PARSE_TIMEOUT",
                      "PARSE_RESOURCE_LIMIT",
                      "PARSING_FAILED",
                      "MODEL_CONFIGURATION_REQUIRED")
                  .contains(code);
          String next = retryable && !terminal && num(j, "attempts") < 3 ? "QUEUED" : "FAILED";
          db.exec(
              "UPDATE jobs SET state=?,error_code=?,lease_token=NULL,lease_until=NULL WHERE id=?",
              next,
              code.replaceAll("[^A-Z0-9_]", "")
                  .substring(0, Math.min(code.replaceAll("[^A-Z0-9_]", "").length(), 90)),
              id);
          db.exec("UPDATE document_versions SET state=? WHERE id=?", next, str(j, "version_id"));
          if (next.equals("QUEUED")) db.exec("INSERT INTO outbox(id,job_id) VALUES(?,?)", id(), id);
        });
  }
}
