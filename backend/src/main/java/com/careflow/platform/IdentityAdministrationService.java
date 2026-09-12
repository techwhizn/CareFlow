package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import java.util.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

@Service
public class IdentityAdministrationService {
  public record TenantChange(@NotBlank @Size(max = 200) String name, @Min(0) long revision) {}
  private final Db db;
  private final Identity auth;
  private final String defaultAccessToken;

  public IdentityAdministrationService(Db db, Identity auth, @Value("${careflow.default-access-token:}") String defaultAccessToken) {
    this.db = db;
    this.auth = auth;
    this.defaultAccessToken = defaultAccessToken;
  }

  public Object me(Actor actor) {
    return me(actor, null);
  }

  public Object me(Actor actor, String authorization) {
    boolean defaultCredential = authorization != null && authorization.startsWith("Bearer ")
        && defaultAccessToken != null && !defaultAccessToken.isBlank()
        && Identity.hash(authorization.substring(7)).equals(Identity.hash(defaultAccessToken));
    return Map.of(
        "tenant_id",
        actor.tenant(),
        "subject_id",
        actor.subject(),
        "role",
        actor.role(),
        "tenant",
        db.one("SELECT name FROM tenants WHERE id=?", actor.tenant()),
        "default_credential",
        defaultCredential);
  }

  public Object tenant(Actor actor) {
    auth.admin(actor);
    return db.one("SELECT id,name,revision FROM tenants WHERE id=?", actor.tenant());
  }

  @Transactional
  public Object changeTenant(Actor actor, TenantChange input) {
    auth.admin(actor);
    auth.lock(actor);
    if (db.exec(
            "UPDATE tenants SET name=?,revision=revision+1 WHERE id=? AND revision=?",
            input.name().trim(), actor.tenant(), input.revision())
        != 1) throw ApiException.conflict();
    auth.audit(actor, "TENANT_UPDATE", actor.tenant(), "name_changed");
    return tenant(actor);
  }

  public Object keys(Actor actor) {
    auth.admin(actor);
    return db.list(
        "SELECT id,subject_id,kind,active,expires_at,scopes FROM credentials WHERE tenant_id=?",
        actor.tenant());
  }

  @Transactional
  public Map<String, String> rotateOwnCredential(Actor actor) {
    if (actor.app()) throw ApiException.hidden();
    auth.lock(actor);
    db.exec("UPDATE credentials SET active=FALSE WHERE tenant_id=? AND subject_id=? AND kind='MEMBER' AND active=TRUE", actor.tenant(), actor.subject());
    String token = auth.credential(actor.tenant(), actor.subject(), "MEMBER", null);
    auth.audit(actor, "MEMBER_CREDENTIAL_ROTATE_SELF", actor.subject(), "old_credentials_revoked");
    return Map.of("token", token);
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
