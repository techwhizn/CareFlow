package com.careflow.platform;

import static com.careflow.platform.Db.*;

import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Deployment operator provisioning; never grants existing tenant data access. */
@Service
public class EnterpriseProvisioning {
  public record ProvisionEnterprise(
      @NotBlank @Size(max = 200) String name, @NotBlank @Size(max = 200) String owner_name) {}

  private final Db db;
  private final Identity auth;
  private final String bootstrap;

  public EnterpriseProvisioning(
      Db db, Identity auth, @Value("${careflow.bootstrap-token}") String bootstrap) {
    this.db = db;
    this.auth = auth;
    this.bootstrap = bootstrap;
  }

  @Transactional
  public Map<String, String> create(String supplied, String key, ProvisionEnterprise input) {
    if (bootstrap.length() < 32
        || supplied == null
        || !MessageDigest.isEqual(
            bootstrap.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
      throw new ApiException(401, "UNAUTHENTICATED", "开通密钥无效");
    if (key == null || key.isBlank() || key.length() > 100) throw new IllegalArgumentException();
    String tenant = id(), owner = id();
    if (!db.list("SELECT tenant_id FROM enterprise_provisions WHERE request_key=?", key).isEmpty())
      throw new ApiException(409, "DUPLICATE_REQUEST", "开通请求已处理；凭证不能再次显示");
    db.exec("INSERT INTO enterprise_provisions(request_key,tenant_id) VALUES(?,?)", key, tenant);
    db.exec("INSERT INTO tenants(id,name) VALUES(?,?)", tenant, input.name());
    db.exec(
        "INSERT INTO members(id,tenant_id,name,role) VALUES(?,?,?,'OWNER')",
        owner,
        tenant,
        input.owner_name());
    auth.audit(
        new Identity.Actor(tenant, owner, "MEMBER", "OWNER"),
        "ENTERPRISE_PROVISION",
        tenant,
        "deployment_operator");
    return Map.of(
        "tenant_id",
        tenant,
        "member_id",
        owner,
        "token",
        auth.credential(tenant, owner, "MEMBER", null));
  }
}
