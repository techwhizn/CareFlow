package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Application-wide limits are serialized by the same tenant lock as quota reservations. */
@Service
public class ApplicationAdmissionService {
  private final Db db;
  private final Identity auth;

  public ApplicationAdmissionService(Db db, Identity auth) {
    this.db = db;
    this.auth = auth;
  }

  public record Limits(
      @Min(1) @Max(6000) int requests_per_minute,
      @Min(1) @Max(100) int concurrent_requests,
      @Min(0) long revision) {}

  public Map<String, Object> limits(Actor actor, String application) {
    auth.developer(actor);
    return configuration(actor.tenant(), application);
  }

  @Transactional
  public Object update(Actor actor, String application, Limits body) {
    auth.lock(actor);
    auth.developer(actor);
    configuration(actor.tenant(), application);
    if (body.requests_per_minute() < 1
        || body.requests_per_minute() > 6000
        || body.concurrent_requests() < 1
        || body.concurrent_requests() > 100
        || body.revision() < 0) throw new IllegalArgumentException();
    if (db.exec(
            "UPDATE applications SET requests_per_minute=?, concurrent_requests=?, limits_revision=limits_revision+1 WHERE tenant_id=? AND id=? AND limits_revision=?",
            body.requests_per_minute(),
            body.concurrent_requests(),
            actor.tenant(),
            application,
            body.revision())
        != 1) throw new ApiException(409, "REVISION_CONFLICT", "应用限流配置已改变，请刷新后重试");
    auth.audit(actor, "APP_LIMITS_UPDATE", application, "");
    return configuration(actor.tenant(), application);
  }

  private Map<String, Object> configuration(String tenant, String application) {
    return db.one(
        "SELECT requests_per_minute,concurrent_requests,limits_revision AS revision FROM applications WHERE tenant_id=? AND id=?",
        tenant,
        application);
  }

  // Called inside RetrievalService.reserve after acquiring Identity.lock; no process-local
  // counters.
  public void admit(Actor actor, String application) {
    if (application == null || application.isBlank()) return;
    var config = configuration(actor.tenant(), application);
    var count =
        db.one(
            "SELECT COALESCE(SUM(CASE WHEN created_at > TIMESTAMPADD(SECOND,-60,CURRENT_TIMESTAMP) THEN 1 ELSE 0 END),0) AS recent, COALESCE(SUM(CASE WHEN state='RESERVED' THEN 1 ELSE 0 END),0) AS running FROM usage_events WHERE tenant_id=? AND application_id=? AND resource_type='QUERY' AND (created_at > TIMESTAMPADD(SECOND,-60,CURRENT_TIMESTAMP) OR state='RESERVED')",
            actor.tenant(),
            application);
    if (num(count, "running") >= num(config, "concurrent_requests"))
      throw new ApiException(429, "APPLICATION_CONCURRENCY_LIMIT", "应用并发调用已达上限");
    if (num(count, "recent") >= num(config, "requests_per_minute"))
      throw new ApiException(429, "APPLICATION_RATE_LIMIT", "应用每分钟调用已达上限");
  }
}
