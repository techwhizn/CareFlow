package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UsageAdministrationService {
  private final Db db;
  private final Identity auth;

  public UsageAdministrationService(Db db, Identity auth) {
    this.db = db;
    this.auth = auth;
  }

  public record Quota(@Min(0) long limit, @NotBlank String reason) {}

  public Object usage(Actor actor) {
    auth.admin(actor);
    return Map.of(
        "quota",
        db.one(
            "SELECT query_limit,queries_used,queries_reserved FROM tenants WHERE id=?",
            actor.tenant()),
        "events",
        db.list(
            "SELECT * FROM usage_events WHERE tenant_id=? ORDER BY created_at DESC LIMIT 200",
            actor.tenant()));
  }

  @Transactional
  public void quota(Actor actor, Quota body) {
    auth.admin(actor);
    auth.lock(actor);
    var old = db.one("SELECT query_limit FROM tenants WHERE id=?", actor.tenant());
    db.exec("UPDATE tenants SET query_limit=? WHERE id=?", body.limit(), actor.tenant());
    auth.audit(
        actor,
        "QUOTA_ADJUST",
        actor.tenant(),
        "before="
            + old.get("query_limit")
            + ", after="
            + body.limit()
            + ", reason="
            + body.reason());
  }
}
