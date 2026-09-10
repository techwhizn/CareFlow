package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Aggregates known provider counts separately from missing reports; no query or answer bodies. */
@Service
public class ModelUsageReadService {
  private final ApplicationUsageAccess applications;
  private final OcrAccountingService ocr;
  private final Db db;
  private final Identity auth;

  public ModelUsageReadService(
      Db db, Identity auth, OcrAccountingService ocr, ApplicationUsageAccess applications) {
    this.applications = applications;
    this.ocr = ocr;
    this.db = db;
    this.auth = auth;
  }

  public Object tenant(Actor actor) {
    auth.admin(actor);
    return summary(actor.tenant(), null);
  }

  public Object application(Actor actor, String app) {
    applications.require(actor, app);
    return summary(actor.tenant(), app);
  }

  private Object summary(String tenant, String app) {
    String scope = app == null ? "" : " AND u.application_id=?";
    Object[] args = app == null ? new Object[] {tenant} : new Object[] {tenant, app};
    var retrieval =
        db.list(
            "SELECT c.stage,COUNT(*) AS calls,COALESCE(SUM(c.total_tokens),0) AS known_tokens,COALESCE(SUM(CASE WHEN c.usage_state IN ('UNKNOWN','NOT_REPORTED') THEN 1 ELSE 0 END),0) AS unknown_usage_calls,COALESCE(SUM(CASE WHEN c.usage_state='NOT_CALLED' THEN 1 ELSE 0 END),0) AS not_called FROM retrieval_model_calls c JOIN usage_events u ON u.id=c.request_id AND u.tenant_id=c.tenant_id WHERE c.tenant_id=?"
                + scope
                + " GROUP BY c.stage",
            args);
    var generation =
        db.one(
            "SELECT COUNT(*) AS requests,COALESCE(SUM(g.total_tokens),0) AS known_tokens,COALESCE(SUM(g.input_tokens),0) AS known_input_tokens,COALESCE(SUM(g.output_tokens),0) AS known_output_tokens,COALESCE(SUM(CASE WHEN g.total_tokens IS NULL AND g.usage_state<>'NOT_CALLED' THEN 1 ELSE 0 END),0) AS unknown_usage_calls FROM generation_usage g JOIN usage_events u ON u.id=g.request_id AND u.tenant_id=g.tenant_id WHERE g.tenant_id=?"
                + scope,
            args);
    var requests =
        db.list(
            "SELECT state,COUNT(*) AS requests FROM usage_events u WHERE tenant_id=? AND resource_type='QUERY'"
                + scope
                + " GROUP BY state",
            args);
    var response = new java.util.LinkedHashMap<String, Object>();
    response.put("retrieval", retrieval);
    response.put("generation", generation);
    response.put("requests", requests);
    response.put("scope", app == null ? "TENANT" : "APPLICATION");
    if (app == null)
      response.put(
          "indexing",
          db.one(
              "SELECT COUNT(*) AS calls,COALESCE(SUM(tokens),0) AS known_tokens,COALESCE(SUM(CASE WHEN tokens IS NULL THEN 1 ELSE 0 END),0) AS unknown_usage_calls FROM processing_model_calls WHERE tenant_id=?",
              tenant));
    if (app == null) {
      response.put("ocr", ocr.summary(tenant, null));
      response.put(
          "storage",
          db.one(
              "SELECT COALESCE(SUM(bytes),0) AS known_source_bytes,COUNT(*) AS source_objects,COALESCE(SUM(CASE WHEN bytes IS NULL THEN 1 ELSE 0 END),0) AS unknown_size_objects FROM (SELECT object_key,MAX(size_bytes) AS bytes FROM document_versions WHERE tenant_id=? AND object_key<>'' GROUP BY object_key) objects",
              tenant));
    }
    response.put(
        "coverage",
        Map.of(
            "retrieval",
            "SINCE_V29",
            "generation",
            "SINCE_V24",
            "indexing",
            "SINCE_V21",
            "ocr",
            "SINCE_V30"));
    return response;
  }
}
