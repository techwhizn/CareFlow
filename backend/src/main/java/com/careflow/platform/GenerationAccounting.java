package com.careflow.platform;

import java.util.Map;
import org.springframework.stereotype.Service;

/** Provider consumption is independent of whether answer delivery succeeded. No text or keys. */
@Service
public class GenerationAccounting {
  private final QueryReservationService reservations;
  private final Db db;

  public GenerationAccounting(Db db, QueryReservationService reservations) {
    this.reservations = reservations;
    this.db = db;
  }

  public void initialize(String tenant, String request, String configuration) {
    if (db.exec(
            "INSERT INTO generation_usage(request_id,tenant_id,subject_id,configuration_id) SELECT id,tenant_id,subject_id,? FROM usage_events WHERE tenant_id=? AND id=? AND state='RESERVED'",
            configuration,
            tenant,
            request)
        != 1) throw new IllegalStateException("Missing query reservation");
  }

  public void started(String tenant, String request) {
    reservations.requireActive(tenant, request);
    if (db.exec(
            "UPDATE generation_usage SET usage_state='STARTED' WHERE tenant_id=? AND request_id=? AND request_state='RUNNING' AND usage_state='NOT_CALLED'",
            tenant,
            request)
        != 1) throw new IllegalStateException("Generation already started or completed");
  }

  public void reported(String tenant, String request, Map<String, Object> usage) {
    Long input = count(usage.get("input_tokens")),
        output = count(usage.get("output_tokens")),
        total = count(usage.get("total_tokens"));
    String state = input != null || output != null || total != null ? "REPORTED" : "NOT_REPORTED";
    if (db.exec(
            "UPDATE generation_usage SET usage_state=CASE WHEN usage_state='REPORTED' THEN 'REPORTED' ELSE ? END,input_tokens=COALESCE(?,input_tokens),output_tokens=COALESCE(?,output_tokens),total_tokens=COALESCE(?,total_tokens) WHERE tenant_id=? AND request_id=? AND usage_state<>'NOT_CALLED'",
            state,
            input,
            output,
            total,
            tenant,
            request)
        != 1) throw new IllegalStateException("Generation usage has no active call");
  }

  private Long count(Object value) {
    if (value == null) return null;
    if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < 0)
      throw new IllegalArgumentException("Usage must be a non-negative reported integer");
    return ((Number) value).longValue();
  }

  public void finish(String tenant, String request, boolean success, boolean cancelled) {
    db.exec(
        "UPDATE generation_usage SET request_state=?,usage_state=CASE WHEN usage_state='STARTED' THEN 'UNKNOWN' ELSE usage_state END,completed_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND request_id=? AND request_state='RUNNING'",
        success ? "SUCCEEDED" : cancelled ? "CANCELLED" : "FAILED",
        tenant,
        request);
  }
}
