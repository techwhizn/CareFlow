package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Retains operational metadata while removing debug questions and expired audit entries. */
@Service
public class RetentionService {
  private final Db db;
  private final Identity auth;
  private final TransactionTemplate tx;
  private final boolean scheduled;

  public RetentionService(
      Db db,
      Identity auth,
      TransactionTemplate tx,
      @Value("${careflow.scheduling:true}") boolean scheduled) {
    this.db = db;
    this.auth = auth;
    this.tx = tx;
    this.scheduled = scheduled;
  }

  public record Input(
      boolean debug_body_collection,
      @Min(1) @Max(3650) int debug_retention_days,
      @Min(1) @Max(3650) int audit_retention_days,
      @Min(0) long revision) {}

  private Map<String, Object> settings(String tenant) {
    return db.one(
        "SELECT debug_body_collection,debug_retention_days,audit_retention_days,retention_revision AS revision FROM tenants WHERE id=?",
        tenant);
  }

  public Object read(Actor actor) {
    auth.admin(actor);
    return settings(actor.tenant());
  }

  public Object update(Actor actor, Input input) {
    if (input.debug_retention_days() < 1
        || input.debug_retention_days() > 3650
        || input.audit_retention_days() < 1
        || input.audit_retention_days() > 3650) throw new IllegalArgumentException();
    return tx.execute(
        status -> {
          auth.lock(actor);
          auth.admin(actor);
          if (db.exec(
                  "UPDATE tenants SET debug_body_collection=?,debug_retention_days=?,audit_retention_days=?,retention_revision=retention_revision+1 WHERE id=? AND retention_revision=?",
                  input.debug_body_collection(),
                  input.debug_retention_days(),
                  input.audit_retention_days(),
                  actor.tenant(),
                  input.revision())
              != 1) throw ApiException.conflict();
          auth.audit(
              actor,
              "RETENTION_UPDATE",
              actor.tenant(),
              "debug_days="
                  + input.debug_retention_days()
                  + ",audit_days="
                  + input.audit_retention_days()
                  + ",collect="
                  + input.debug_body_collection());
          return settings(actor.tenant());
        });
  }

  public boolean collect(String tenant) {
    return bool(settings(tenant), "debug_body_collection");
  }

  @Scheduled(fixedDelay = 300000)
  public void sweep() {
    if (!scheduled) return;
    for (var tenant : db.list("SELECT id FROM tenants")) sweepTenant(str(tenant, "id"));
  }

  public void sweepTenant(String tenant) {
    tx.executeWithoutResult(
        status -> {
          db.one("SELECT id FROM tenants WHERE id=? FOR UPDATE", tenant);
          var policy = settings(tenant);
          boolean collect = bool(policy, "debug_body_collection");
          var questions =
              db.list(
                  "SELECT id FROM query_records WHERE tenant_id=? AND body_state='COLLECTED' AND (?=FALSE OR created_at<TIMESTAMPADD(DAY,?,CURRENT_TIMESTAMP)) ORDER BY created_at,id LIMIT 100",
                  tenant,
                  collect,
                  -num(policy, "debug_retention_days"));
          for (var row : questions)
            db.exec(
                "UPDATE query_records SET question='',body_state=? WHERE tenant_id=? AND id=?",
                collect ? "EXPIRED" : "DISABLED",
                tenant,
                str(row, "id"));
          var audit =
              db.list(
                  "SELECT id FROM audit_events WHERE tenant_id=? AND created_at<TIMESTAMPADD(DAY,?,CURRENT_TIMESTAMP) ORDER BY created_at,id LIMIT 100",
                  tenant,
                  -num(policy, "audit_retention_days"));
          for (var row : audit)
            db.exec("DELETE FROM audit_events WHERE tenant_id=? AND id=?", tenant, str(row, "id"));
          db.exec(
              "DELETE FROM integration_deliveries WHERE tenant_id=? AND status IN ('DELIVERED','FAILED') AND created_at<TIMESTAMPADD(DAY,?,CURRENT_TIMESTAMP)",
              tenant,
              -num(policy, "audit_retention_days"));
          if (!questions.isEmpty() || !audit.isEmpty())
            org.slf4j.LoggerFactory.getLogger(RetentionService.class)
                .info(
                    "retention tenant_id={} debug_removed={} audit_removed={}",
                    tenant,
                    questions.size(),
                    audit.size());
        });
  }
}
