package com.careflow.platform;

import static com.careflow.platform.Db.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class Tasks {
  private final Db db;
  private final TransactionTemplate tx;
  private final Identity auth;
  private final EntitlementService entitlements;
  private final KnowledgeConfigurationService configurations;
  private final ParsedContentService parsedContent;
  private final IndexAccountingService accounting;
  private final IndexGenerationService generations;

  public Tasks(
      Db db,
      TransactionTemplate tx,
      Identity auth,
      EntitlementService entitlements,
      KnowledgeConfigurationService configurations,
      ParsedContentService parsedContent,
      IndexAccountingService accounting,
      IndexGenerationService generations) {
    this.db = db;
    this.tx = tx;
    this.auth = auth;
    this.entitlements = entitlements;
    this.configurations = configurations;
    this.parsedContent = parsedContent;
    this.accounting = accounting;
    this.generations = generations;
  }

  @Bean
  Queue processingQueue() {
    return new Queue("careflow.processing", true);
  }

  public void recover() {
    generations.recoverTerminalJobs();
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
                "UPDATE jobs SET state=?,dispatch_until=NULL,dispatch_token=NULL,wait_reason=NULL,lease_token=NULL,lease_until=NULL,error_code='LEASE_EXPIRED' WHERE id=?",
                next,
                str(job, "id"));
            generations.abandon(job, next.equals("CANCELLED") ? "CANCELLED" : "FAILED");
            if (!bool(job, "index_rebuild"))
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
          generations.abandon(job, "CANCELLED");
          if (!bool(job, "index_rebuild"))
            db.exec(
                "UPDATE document_versions SET state='FAILED' WHERE id=?", str(job, "version_id"));
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
    var result =
        tx.execute(
            status -> {
              var tenantRow = db.one("SELECT tenant_id FROM jobs WHERE id=?", id);
              db.one("SELECT id FROM tenants WHERE id=? FOR UPDATE", str(tenantRow, "tenant_id"));
              var j = db.one("SELECT * FROM jobs WHERE id=? FOR UPDATE", id);
              if (!str(j, "state").equals("QUEUED")) throw ApiException.conflict();
              var v =
                  db.one(
                      "SELECT v.* FROM document_versions v JOIN documents d ON d.id=v.document_id JOIN knowledge_bases k ON k.id=d.kb_id WHERE v.id=? AND d.status='ACTIVE' AND k.status='ACTIVE'",
                      str(j, "version_id"));
              KnowledgeConfiguration.RuntimeConfiguration configuration;
              try {
                configuration =
                    configurations.runtime(str(j, "tenant_id"), str(j, "configuration_id"));
                if (configuration == null && str(j, "kind").equals("INDEX"))
                  throw new ApiException(409, "MODEL_CONFIGURATION_REQUIRED", "索引任务缺少冻结配置");
              } catch (ApiException e) {
                db.exec(
                    "UPDATE jobs SET state='FAILED',error_code='MODEL_CONFIGURATION_REQUIRED',dispatch_until=NULL,dispatch_token=NULL,wait_reason=NULL WHERE id=?",
                    id);
                if (!bool(j, "index_rebuild"))
                  db.exec(
                      "UPDATE document_versions SET state='FAILED' WHERE id=?",
                      str(j, "version_id"));
                return Map.<String, Object>of("configuration_unavailable", true);
              }
              entitlements.claimTask(str(j, "tenant_id"), j);
              String lease = id();
              db.exec(
                  "UPDATE jobs SET state='RUNNING',dispatch_until=NULL,wait_reason=NULL,attempts=attempts+1,checkpoint='STARTED',error_code=NULL,heartbeat_at=CURRENT_TIMESTAMP,lease_token=?,lease_until=? WHERE id=?",
                  lease,
                  Timestamp.from(Instant.now().plusSeconds(90)),
                  id);
              if (!bool(j, "index_rebuild"))
                db.exec(
                    "UPDATE document_versions SET state=? WHERE id=?",
                    str(j, "kind").equals("PARSE") ? "PARSING" : "INDEXING",
                    str(j, "version_id"));
              var claim =
                  new LinkedHashMap<String, Object>(
                      Map.of(
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
                          str(v, "filename"),
                          "pdf_page_limit",
                          num(
                              db.one(
                                  "SELECT pdf_page_limit FROM tenants WHERE id=?",
                                  str(j, "tenant_id")),
                              "pdf_page_limit")));
              if (configuration != null) claim.put("configuration", configuration);
              String generation = generations.start(j, v, lease);
              if (generation != null) claim.put("generation_id", generation);
              return claim;
            });
    if (result.containsKey("configuration_unavailable"))
      throw new ApiException(409, "MODEL_CONFIGURATION_REQUIRED", "任务配置不可用，任务已标记失败");
    return result;
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
            String configuration = str(j, "configuration_id");
            parsedContent.replace(
                tenant,
                version,
                body,
                configuration.isBlank()
                    ? new KnowledgeConfiguration.Chunking(400, 600, 60)
                    : configurations.definition(tenant, configuration).chunking());
            db.exec("UPDATE document_versions SET state='PARSED' WHERE id=?", version);
          } else {
            if (!Boolean.TRUE.equals(body.get("verified")) || str(body, "model_identity").isBlank())
              throw new IllegalArgumentException();
            var configuration = configurations.runtime(tenant, str(j, "configuration_id"));
            if (configuration != null
                && !configurations
                    .modelIdentity(configuration.embedding())
                    .equals(str(body, "model_identity")))
              throw new ApiException(409, "INDEX_MODEL_MISMATCH", "索引模型与任务快照不一致");
            accounting.complete(j, lease, body);
            generations.activate(j, lease, body);
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

  public void recordModelCall(
      String id, String lease, String callId, IndexAccountingService.Call call) {
    tx.executeWithoutResult(
        status -> {
          var tenant = db.one("SELECT tenant_id FROM jobs WHERE id=?", id);
          db.one("SELECT id FROM tenants WHERE id=? FOR UPDATE", str(tenant, "tenant_id"));
          var job =
              call.state().equals("STARTED")
                  ? validate(id, lease)
                  : db.one("SELECT * FROM jobs WHERE id=? FOR UPDATE", id);
          accounting.record(job, lease, callId, call);
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
              "UPDATE jobs SET state=?,dispatch_until=NULL,dispatch_token=NULL,wait_reason=NULL,error_code=?,lease_token=NULL,lease_until=NULL WHERE id=?",
              next,
              code.replaceAll("[^A-Z0-9_]", "")
                  .substring(0, Math.min(code.replaceAll("[^A-Z0-9_]", "").length(), 90)),
              id);
          generations.abandon(j, "FAILED");
          if (!bool(j, "index_rebuild"))
            db.exec("UPDATE document_versions SET state=? WHERE id=?", next, str(j, "version_id"));
          if (next.equals("QUEUED")) db.exec("INSERT INTO outbox(id,job_id) VALUES(?,?)", id(), id);
        });
  }
}
