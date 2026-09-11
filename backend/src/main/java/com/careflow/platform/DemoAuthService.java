package com.careflow.platform;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit local-test login; disabled unless the deployment opts in. */
@Service
public class DemoAuthService {
  private static final String INITIAL_TENANT = "00000000-0000-0000-0000-000000000001";
  private final Db db;
  private final Identity identity;
  private final boolean enabled;

  public DemoAuthService(
      Db db, Identity identity, @Value("${careflow.demo-auth-enabled:false}") boolean enabled) {
    this.db = db;
    this.identity = identity;
    this.enabled = enabled;
  }

  public boolean enabled() {
    return enabled;
  }

  @Transactional
  public Map<String, Object> login() {
    if (!enabled) throw ApiException.hidden();
    var owner =
        db.one(
            "SELECT id,role FROM members WHERE tenant_id=? AND role='OWNER' "
                + "AND active=TRUE AND removed=FALSE ORDER BY created_at LIMIT 1",
            INITIAL_TENANT);
    String member = Db.str(owner, "id");
    String token =
        identity.credential(
            INITIAL_TENANT,
            member,
            "MEMBER",
            Timestamp.from(Instant.now().plus(8, ChronoUnit.HOURS)));
    identity.audit(
        new Identity.Actor(INITIAL_TENANT, member, "MEMBER", "OWNER"),
        "DEMO_LOGIN",
        member,
        "short_lived_test_credential");
    return Map.of("token", token, "expires_in_hours", 8);
  }
}
