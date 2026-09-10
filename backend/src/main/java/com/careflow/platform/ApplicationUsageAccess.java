package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import org.springframework.stereotype.Service;

@Service
public class ApplicationUsageAccess {
  private final Db db;
  private final Identity auth;

  public ApplicationUsageAccess(Db db, Identity auth) {
    this.db = db;
    this.auth = auth;
  }

  public void require(Actor actor, String app) {
    if (actor.app()) {
      if (!actor.subject().equals(app)) throw ApiException.hidden();
    } else auth.developer(actor);
    db.one("SELECT id FROM applications WHERE tenant_id=? AND id=?", actor.tenant(), app);
  }
}
