package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IndexMaintenanceService {
  private final BillingRulesService billing;
  private final Db db;
  private final Identity auth;
  private final KnowledgeConfigurationService configurations;
  private final WorkerClient worker;
  private final TransactionTemplate tx;
  private final ObjectMapper json;

  public IndexMaintenanceService(
      BillingRulesService billing,
      Db db,
      Identity auth,
      KnowledgeConfigurationService configurations,
      WorkerClient worker,
      TransactionTemplate tx,
      ObjectMapper json) {
    this.billing = billing;
    this.db = db;
    this.auth = auth;
    this.configurations = configurations;
    this.worker = worker;
    this.tx = tx;
    this.json = json;
  }

  public record Rebuild(
      @NotNull @Min(0) Long revision,
      @Size(max = 36) String expected_generation_id,
      @NotBlank @Size(max = 1000) String reason) {}

  public Object rebuild(Actor actor, String version, String key, Rebuild input) {
    return tx.execute(
        status -> {
          auth.lock(actor);
          var current = activeVersion(actor, version);
          if (!str(current, "state").equals("READY") || str(current, "configuration_id").isBlank())
            throw new ApiException(409, "REBUILD_NOT_READY", "需要已有就绪索引及其原处理配置；未绑定配置的历史版本请先绑定兼容配置");
          if (input.revision() == null
              || input.revision() != num(current, "revision")
              || !Objects.toString(input.expected_generation_id(), "")
                  .equals(str(current, "active_index_generation"))) throw ApiException.conflict();
          if (key.isBlank() || key.length() > 100) throw new IllegalArgumentException();
          if (!db.list(
                  "SELECT id FROM jobs WHERE tenant_id=? AND version_id=? AND state IN ('QUEUED','RUNNING')",
                  actor.tenant(),
                  version)
              .isEmpty()) throw new ApiException(409, "INDEX_TASK_ACTIVE", "此版本已有处理任务");
          configurations.runtime(actor.tenant(), str(current, "configuration_id"));
          String job = id();
          boolean preserve = bool(current, "ever_published");
          db.exec(
              "INSERT INTO jobs(id,tenant_id,version_id,kind,request_key,configuration_id,index_rebuild) VALUES(?,?,?,'INDEX',?,?,?)",
              job,
              actor.tenant(),
              version,
              key,
              str(current, "configuration_id"),
              preserve);
          billing.attachJob(actor.tenant(), job, 0);
          db.exec("UPDATE jobs SET http_request_id=? WHERE id=?", TraceContext.current(), job);
          db.exec("INSERT INTO outbox(id,job_id) VALUES(?,?)", id(), job);
          if (!preserve)
            db.exec(
                "UPDATE document_versions SET state='QUEUED' WHERE tenant_id=? AND id=?",
                actor.tenant(),
                version);
          auth.audit(actor, "INDEX_REBUILD", version, input.reason());
          return Map.of(
              "job_id",
              job,
              "previous_generation",
              str(current, "active_index_generation"),
              "published_index_preserved",
              preserve);
        });
  }

  public Object inspect(Actor actor, String authorization, String version) {
    var snapshot = activeVersion(actor, version);
    if (!str(snapshot, "state").equals("READY"))
      throw new ApiException(409, "NOT_READY", "请等待索引完成后核对");
    if (str(snapshot, "configuration_id").isBlank())
      throw new ApiException(409, "MODEL_CONFIGURATION_REQUIRED", "请先为历史版本绑定兼容配置");
    var runtime = configurations.runtime(actor.tenant(), str(snapshot, "configuration_id"));
    var chunks =
        IndexManifest.entries(
            db.list(
                "SELECT id,content FROM chunks WHERE tenant_id=? AND version_id=? AND enabled=TRUE",
                actor.tenant(),
                version));
    var request =
        new WorkerProtocolV1.IndexVerification(
            actor.tenant(),
            version,
            snapshot.get("active_index_generation") == null
                ? null
                : str(snapshot, "active_index_generation"),
            str(snapshot, "model_identity"),
            runtime.embedding(),
            chunks);
    var report = worker.call("/internal/v1/index/verify", request);
    boolean claimed = Boolean.TRUE.equals(report.get("consistent"));
    if (num(report, "expected_count") != chunks.size()
        || (claimed
            && (num(report, "actual_count") != chunks.size()
                || num(report, "missing_count") != 0
                || num(report, "extra_count") != 0
                || num(report, "mismatched_count") != 0
                || !IndexManifest.digest(chunks).equals(str(report, "manifest")))))
      throw new ApiException(503, "INVALID_INDEX_REPORT", "索引核对结果不符合当前内容快照");
    return tx.execute(
        status -> {
          auth.lock(actor);
          if (!actor.equals(auth.authenticate(authorization))) throw ApiException.hidden();
          var current = activeVersion(actor, version);
          if (num(current, "revision") != num(snapshot, "revision")
              || !str(current, "active_index_generation")
                  .equals(str(snapshot, "active_index_generation"))
              || !str(current, "state").equals("READY")) throw ApiException.conflict();
          String check = id();
          db.exec(
              "INSERT INTO index_checks(id,tenant_id,version_id,generation_id,content_revision,actor_id,report_json) VALUES(?,?,?,?,?,?,?)",
              check,
              actor.tenant(),
              version,
              current.get("active_index_generation"),
              num(current, "revision"),
              actor.subject(),
              encode(report));
          auth.audit(actor, "INDEX_CHECK", version, "consistent=" + claimed + ", check=" + check);
          return Map.of("id", check, "report", report);
        });
  }

  public Object history(Actor actor, String version) {
    auth.version(actor, version, "edit");
    return Map.of(
        "generations",
        db.list(
            "SELECT id,model_identity,content_revision,previous_generation,state,manifest,chunk_count,created_at,completed_at FROM index_generations WHERE tenant_id=? AND version_id=? ORDER BY created_at DESC,sequence_no DESC LIMIT 100",
            actor.tenant(),
            version),
        "checks",
        db.list(
            "SELECT id,generation_id,content_revision,actor_id,report_json,created_at FROM index_checks WHERE tenant_id=? AND version_id=? ORDER BY created_at DESC,sequence_no DESC LIMIT 50",
            actor.tenant(),
            version));
  }

  private Map<String, Object> activeVersion(Actor actor, String version) {
    var current = auth.version(actor, version, "edit");
    var document = auth.document(actor, str(current, "document_id"), "edit");
    if (!str(document, "status").equals("ACTIVE")
        || !str(auth.kb(actor, str(document, "kb_id"), "edit"), "status").equals("ACTIVE"))
      throw ApiException.hidden();
    return current;
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
