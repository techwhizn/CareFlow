package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import com.careflow.platform.RetrievalService.Scope;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Owns admission, durable completion markers and recovery under one tenant lock. */
@Service
public class QueryReservationService {
  private final BillingRulesService billing;
  private final Db db;
  private final Identity auth;
  private final TransactionTemplate tx;
  private final EntitlementService entitlements;
  private final ApplicationAdmissionService admission;
  private final boolean scheduled;

  public QueryReservationService(
      BillingRulesService billing,
      Db db,
      Identity auth,
      TransactionTemplate tx,
      EntitlementService entitlements,
      ApplicationAdmissionService admission,
      @Value("${careflow.scheduling:true}") boolean scheduled) {
    this.billing = billing;
    this.db = db;
    this.auth = auth;
    this.tx = tx;
    this.entitlements = entitlements;
    this.admission = admission;
    this.scheduled = scheduled;
  }

  public String reserve(Actor actor, String key, String app) {
    return reserve(actor, key, app, null, "UNKNOWN");
  }

  public String reserve(Actor actor, String key, Scope scope, String operation) {
    return reserve(actor, key, scope.application(), scope, operation);
  }

  private String reserve(Actor actor, String key, String app, Scope scope, String operation) {
    if (!Set.of("SEARCH", "ANSWER", "UNKNOWN").contains(operation))
      throw new IllegalArgumentException();
    return tx.execute(
        status -> {
          auth.lock(actor);
          if (key == null || key.isBlank() || key.length() > 100)
            throw new IllegalArgumentException();
          var previous =
              db.list(
                  "SELECT id FROM usage_events WHERE tenant_id=? AND subject_id=? AND request_key=? AND resource_type='QUERY'",
                  actor.tenant(),
                  actor.subject(),
                  key);
          if (!previous.isEmpty())
            throw new ApiException(409, "DUPLICATE_REQUEST", "此请求已处理或处理中，请查询历史结果，勿重复计费");
          admission.admit(actor, app);
          entitlements.query(actor.tenant());
          if (db.exec(
                  "UPDATE tenants SET queries_reserved=queries_reserved+1 WHERE id=? AND queries_used+queries_reserved<query_limit",
                  actor.tenant())
              != 1) throw new ApiException(429, "QUOTA_EXCEEDED", "查询额度不足");
          String event = id();
          db.exec(
              "INSERT INTO usage_events(id,tenant_id,application_id,subject_id,request_key,resource_type,amount,state) VALUES(?,?,?,?,?,'QUERY',1,'RESERVED')",
              event,
              actor.tenant(),
              app,
              actor.subject(),
              key);
          db.exec(
              "UPDATE usage_events SET expires_at=TIMESTAMPADD(SECOND,300,CURRENT_TIMESTAMP),operation=?,outcome='RUNNING',application_revision=?,configuration_id=?,application_configuration_id=? WHERE id=?",
              operation,
              scope == null ? null : scope.applicationRevision(),
              scope == null || scope.configuration() == null
                  ? null
                  : scope.configuration().runtime().id(),
              scope == null || scope.applicationPolicy() == null
                  ? null
                  : scope.applicationPolicy().id(),
              event);
          billing.attachRequest(actor.tenant(), event);
          db.exec(
              "UPDATE usage_events SET http_request_id=? WHERE id=?",
              TraceContext.current(),
              event);
          return event;
        });
  }

  public void settle(Actor actor, String event, boolean success) {
    settle(actor, event, success, false, null);
  }

  public void settle(Actor actor, String event, boolean success, boolean cancelled, String error) {
    String code = error != null && error.matches("[A-Z][A-Z0-9_]{0,99}") ? error : null;
    tx.executeWithoutResult(
        status -> {
          auth.lock(actor);
          var rows =
              db.list(
                  "SELECT *,COALESCE(expires_at,TIMESTAMPADD(SECOND,300,created_at))>CURRENT_TIMESTAMP AS active FROM usage_events WHERE tenant_id=? AND id=? AND subject_id=? AND resource_type='QUERY'",
                  actor.tenant(),
                  event,
                  actor.subject());
          if (rows.isEmpty()) throw ApiException.hidden();
          var row = rows.getFirst();
          finish(
              row,
              (success && bool(row, "active")) || bool(row, "result_committed"),
              cancelled ? "CANCELLED" : "FAILED",
              code);
        });
  }

  private void finish(Map<String, Object> row, boolean success, String outcome, String error) {
    if (db.exec(
            "UPDATE usage_events SET state=?,outcome=?,error_code=?,completed_at=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=? AND state='RESERVED'",
            success ? "SETTLED" : "RELEASED",
            success ? "SUCCEEDED" : outcome,
            success ? null : error,
            str(row, "id"),
            str(row, "tenant_id"))
        == 1)
      db.exec(
          "UPDATE tenants SET queries_reserved=queries_reserved-1,queries_used=queries_used+? WHERE id=?",
          success ? 1 : 0,
          str(row, "tenant_id"));
  }

  public void requireActive(String tenant, String request) {
    if (request == null) return;
    var rows =
        db.list(
            "SELECT id FROM usage_events WHERE tenant_id=? AND id=? AND resource_type='QUERY' AND state='RESERVED' AND COALESCE(expires_at,TIMESTAMPADD(SECOND,300,created_at))>CURRENT_TIMESTAMP",
            tenant,
            request);
    if (rows.isEmpty()) throw new ApiException(409, "QUERY_EXPIRED", "查询执行已结束或超时，请使用新的请求重新调用");
  }

  // Invoked inside the same tenant-locked transaction as the durable query/answer result.
  public void result(Actor actor, String request, boolean answer) {
    if (request == null) return;
    requireActive(actor.tenant(), request);
    var row =
        db.one(
            "SELECT subject_id,operation FROM usage_events WHERE tenant_id=? AND id=?",
            actor.tenant(),
            request);
    if (!str(row, "subject_id").equals(actor.subject())) throw ApiException.hidden();
    if (answer || str(row, "operation").equals("SEARCH"))
      db.exec(
          "UPDATE usage_events SET result_committed=TRUE WHERE tenant_id=? AND id=?",
          actor.tenant(),
          request);
  }

  @Scheduled(fixedDelay = 15000)
  public void scheduledRecovery() {
    if (scheduled) recover();
  }

  public void recover() {
    for (var candidate :
        db.list(
            "SELECT id,tenant_id FROM usage_events WHERE resource_type='QUERY' AND state='RESERVED' AND COALESCE(expires_at,TIMESTAMPADD(SECOND,300,created_at))<=CURRENT_TIMESTAMP ORDER BY created_at LIMIT 100"))
      tx.executeWithoutResult(
          status -> {
            String tenant = str(candidate, "tenant_id"), request = str(candidate, "id");
            db.one("SELECT id FROM tenants WHERE id=? FOR UPDATE", tenant);
            var rows =
                db.list(
                    "SELECT * FROM usage_events WHERE tenant_id=? AND id=? AND state='RESERVED' AND COALESCE(expires_at,TIMESTAMPADD(SECOND,300,created_at))<=CURRENT_TIMESTAMP",
                    tenant,
                    request);
            if (rows.isEmpty()) return;
            var row = rows.getFirst();
            finish(row, bool(row, "result_committed"), "RECOVERED", "QUERY_EXPIRED");
            db.exec(
                "UPDATE generation_usage SET request_state=?,usage_state=CASE WHEN usage_state='STARTED' THEN 'UNKNOWN' ELSE usage_state END,completed_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND request_id=? AND request_state='RUNNING'",
                bool(row, "result_committed") ? "SUCCEEDED" : "RECOVERED",
                tenant,
                request);
            db.exec(
                "UPDATE conversations SET active_request_id=NULL,busy_until=NULL WHERE tenant_id=? AND active_request_id=?",
                tenant,
                request);
          });
  }
}
