package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.constraints.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApplicationCredentialService {
  public record Options(
      @NotNull @Size(min = 1, max = 3) Set<String> scopes, @Min(1) @Max(365) int expires_in_days) {}

  private final Db db;
  private final Identity auth;

  public ApplicationCredentialService(Db db, Identity auth) {
    this.db = db;
    this.auth = auth;
  }

  private void require(Actor actor, String app) {
    auth.developer(actor);
    db.one("SELECT id FROM applications WHERE tenant_id=? AND id=?", actor.tenant(), app);
  }

  @Transactional
  public Map<String, Object> create(Actor actor, String app, Options options) {
    require(actor, app);
    auth.lock(actor);
    if (options == null) options = new Options(Set.of("READ", "SEARCH", "ANSWER"), 90);
    if (options.scopes().stream().anyMatch(Objects::isNull)
        || !Set.of("READ", "SEARCH", "ANSWER").containsAll(options.scopes()))
      throw new IllegalArgumentException();
    var expires = Timestamp.from(Instant.now().plusSeconds(options.expires_in_days() * 86400L));
    String raw = auth.credential(actor.tenant(), app, "APP", expires);
    String scopes = String.join(",", new TreeSet<>(options.scopes()));
    db.exec(
        "UPDATE credentials SET scopes=? WHERE tenant_id=? AND digest=?",
        scopes,
        actor.tenant(),
        Identity.hash(raw));
    String id =
        str(
            db.one(
                "SELECT id FROM credentials WHERE tenant_id=? AND digest=?",
                actor.tenant(),
                Identity.hash(raw)),
            "id");
    auth.audit(actor, "CREDENTIAL_CREATE", id, "application=" + app + ",scopes=" + scopes);
    return Map.of(
        "id",
        id,
        "token",
        raw,
        "expires_at",
        expires.toInstant().toString(),
        "expires_in_days",
        options.expires_in_days(),
        "scopes",
        options.scopes());
  }

  public List<Map<String, Object>> list(Actor actor, String app) {
    require(actor, app);
    return db.list(
        "SELECT id,subject_id,kind,active,expires_at,scopes FROM credentials WHERE tenant_id=? AND subject_id=? AND kind='APP'",
        actor.tenant(),
        app);
  }

  @Transactional
  public void revoke(Actor actor, String app, String credential) {
    require(actor, app);
    auth.lock(actor);
    if (db.exec(
            "UPDATE credentials SET active=FALSE WHERE tenant_id=? AND id=? AND subject_id=? AND kind='APP'",
            actor.tenant(),
            credential,
            app)
        != 1) throw ApiException.hidden();
    auth.audit(actor, "CREDENTIAL_REVOKE", credential, "application=" + app);
  }
}
