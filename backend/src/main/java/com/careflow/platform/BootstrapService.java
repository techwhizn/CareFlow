package com.careflow.platform;

import static com.careflow.platform.Db.*;

import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BootstrapService {
  private final Db db;
  private final Identity auth;
  private final String bootstrap;

  public BootstrapService(
      Db db,
      Identity auth,
      @org.springframework.beans.factory.annotation.Value("${careflow.bootstrap-token}")
          String bootstrap) {
    this.db = db;
    this.auth = auth;
    this.bootstrap = bootstrap;
  }

  public record Named(
      @NotBlank @Size(max = 200) String name, @Size(max = 2000) String description) {}

  @Transactional
  public Map<String, Object> bootstrap(String token, Named input) {
    if (bootstrap.length() < 32
        || !java.security.MessageDigest.isEqual(
            bootstrap.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            token.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
      throw new ApiException(401, "UNAUTHENTICATED", "初始化密钥无效");
    // Fixed primary key makes concurrent attempts at first initialization mutually exclusive.
    String tenant = "00000000-0000-0000-0000-000000000001", member = id();
    db.exec("INSERT INTO tenants(id,name) VALUES(?,?)", tenant, input.name());
    db.exec(
        "INSERT INTO members(id,tenant_id,name,role) VALUES(?,?,?,'OWNER')",
        member,
        tenant,
        "企业所有者");
    return Map.of(
        "tenant_id",
        tenant,
        "member_id",
        member,
        "token",
        auth.credential(tenant, member, "MEMBER", null));
  }
}
