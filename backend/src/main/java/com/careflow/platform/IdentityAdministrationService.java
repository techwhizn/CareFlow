package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityAdministrationService {
  private final Db db;
  private final Identity auth;

  public IdentityAdministrationService(Db db, Identity auth) {
    this.db = db;
    this.auth = auth;
  }

  public Object me(Actor actor) {
    return Map.of(
        "tenant_id",
        actor.tenant(),
        "subject_id",
        actor.subject(),
        "role",
        actor.role(),
        "tenant",
        db.one("SELECT name FROM tenants WHERE id=?", actor.tenant()));
  }

  public Object keys(Actor actor) {
    auth.admin(actor);
    return db.list(
        "SELECT id,subject_id,kind,active,expires_at,scopes FROM credentials WHERE tenant_id=?",
        actor.tenant());
  }

  @Transactional
  public void revoke(Actor actor, String id) {
    auth.admin(actor);
    auth.lock(actor);
    if (db.exec(
            "UPDATE credentials SET active=FALSE WHERE tenant_id=? AND id=?", actor.tenant(), id)
        != 1) throw ApiException.hidden();
    auth.audit(actor, "CREDENTIAL_REVOKE", id, "");
  }

  public Object audit(Actor actor) {
    auth.admin(actor);
    return db.list(
        "SELECT * FROM audit_events WHERE tenant_id=? ORDER BY created_at DESC LIMIT 200",
        actor.tenant());
  }
}
