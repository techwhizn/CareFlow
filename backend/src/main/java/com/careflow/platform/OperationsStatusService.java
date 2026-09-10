package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * Tenant-scoped aggregate runtime metadata, never resource titles, bodies, credentials or queries.
 */
@Service
public class OperationsStatusService {
  private final Db db;

  public OperationsStatusService(Db db) {
    this.db = db;
  }

  public Map<String, Object> status(Actor actor) {
    if (actor.app() || !Set.of("OPS", "OWNER", "ADMIN").contains(actor.role()))
      throw ApiException.hidden();
    return Map.of(
        "jobs",
            db.list(
                "SELECT kind,state,COUNT(*) AS count FROM jobs WHERE tenant_id=? GROUP BY kind,state ORDER BY kind,state",
                actor.tenant()),
        "cleanup",
            db.list(
                "SELECT state,COUNT(*) AS count FROM cleanup_requests WHERE tenant_id=? GROUP BY state ORDER BY state",
                actor.tenant()),
        "generation",
            db.list(
                "SELECT request_state,COUNT(*) AS count FROM generation_usage WHERE tenant_id=? GROUP BY request_state ORDER BY request_state",
                actor.tenant()),
        "generated_at", java.time.Instant.now().toString());
  }
}
