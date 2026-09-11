package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QualityFeedbackService {
  private final Db db;
  private final Identity auth;

  public QualityFeedbackService(Db db, Identity auth) {
    this.db = db;
    this.auth = auth;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> summary(Actor actor) {
    auth.manager(actor);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put(
        "answers",
        counts(
            "SELECT feedback AS key,COUNT(*) AS value FROM answers WHERE tenant_id=? AND feedback IS NOT NULL GROUP BY feedback",
            actor.tenant()));
    result.put(
        "reasons",
        counts(
            "SELECT feedback_reason AS key,COUNT(*) AS value FROM answers WHERE tenant_id=? AND feedback='incorrect' AND feedback_reason<>'' GROUP BY feedback_reason",
            actor.tenant()));
    result.put(
        "improvements",
        counts(
            "SELECT state AS key,COUNT(*) AS value FROM improvement_tasks WHERE tenant_id=? GROUP BY state",
            actor.tenant()));
    result.put("generated_at", java.time.Instant.now().toString());
    return result;
  }

  private Map<String, Long> counts(String sql, String tenant) {
    Map<String, Long> result = new TreeMap<>();
    for (var row : db.list(sql, tenant)) result.put(Db.str(row, "key"), Db.num(row, "value"));
    return result;
  }
}
