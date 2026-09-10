package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import java.util.*;
import org.springframework.stereotype.Service;

/** Tenant-local gauges suitable for polling. No IDs or content become metric labels. */
@Service
public class OperationsMetricsService {
  private final Db db;

  public OperationsMetricsService(Db db) {
    this.db = db;
  }

  public Map<String, Object> metrics(Actor actor) {
    if (actor.app() || !Set.of("OWNER", "ADMIN", "OPS").contains(actor.role()))
      throw ApiException.hidden();
    var gauges = new LinkedHashMap<String, Long>();
    gauges.put("careflow_jobs_queued", count(actor, "jobs", "state='QUEUED'"));
    gauges.put("careflow_jobs_running", count(actor, "jobs", "state='RUNNING'"));
    gauges.put("careflow_jobs_failed", count(actor, "jobs", "state='FAILED'"));
    gauges.put(
        "careflow_jobs_expired_lease",
        count(actor, "jobs", "state='RUNNING' AND lease_until<CURRENT_TIMESTAMP"));
    gauges.put(
        "careflow_queries_reserved",
        count(actor, "usage_events", "resource_type='QUERY' AND state='RESERVED'"));
    gauges.put(
        "careflow_queries_expired",
        count(
            actor,
            "usage_events",
            "resource_type='QUERY' AND state='RESERVED' AND expires_at<CURRENT_TIMESTAMP"));
    gauges.put(
        "careflow_queries_failed_recent",
        count(
            actor,
            "usage_events",
            "resource_type='QUERY' AND outcome='FAILED' AND created_at>TIMESTAMPADD(MINUTE,-5,CURRENT_TIMESTAMP)"));
    gauges.put("careflow_cleanup_blocked", count(actor, "cleanup_requests", "state='BLOCKED'"));
    var alerts = new ArrayList<Map<String, Object>>();
    for (String name :
        List.of(
            "careflow_jobs_expired_lease", "careflow_queries_expired", "careflow_cleanup_blocked"))
      if (gauges.get(name) > 0)
        alerts.add(Map.of("code", name, "severity", "warning", "count", gauges.get(name)));
    if (gauges.get("careflow_queries_failed_recent") >= 5)
      alerts.add(
          Map.of(
              "code",
              "QUERY_FAILURE_BURST",
              "severity",
              "warning",
              "count",
              gauges.get("careflow_queries_failed_recent")));
    return Map.of(
        "gauges", gauges, "alerts", alerts, "generated_at", java.time.Instant.now().toString());
  }

  private long count(Actor actor, String table, String predicate) {
    // Table and predicate originate exclusively from the fixed calls above.
    return num(
        db.one(
            "SELECT COUNT(*) AS n FROM " + table + " WHERE tenant_id=? AND " + predicate,
            actor.tenant()),
        "n");
  }

  @SuppressWarnings("unchecked")
  public String prometheus(Actor actor) {
    var values = (Map<String, Long>) metrics(actor).get("gauges");
    var output = new StringBuilder();
    values.forEach(
        (name, value) ->
            output
                .append("# TYPE ")
                .append(name)
                .append(" gauge\n")
                .append(name)
                .append(' ')
                .append(value)
                .append('\n'));
    return output.toString();
  }
}
