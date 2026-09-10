package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ManagementController {
  private final Db db;
  private final Identity auth;
  private final String bootstrap;

  public ManagementController(
      Db db, Identity auth, @Value("${careflow.bootstrap-token}") String bootstrap) {
    this.db = db;
    this.auth = auth;
    this.bootstrap = bootstrap;
  }

  public record Named(
      @NotBlank @Size(max = 200) String name, @Size(max = 2000) String description) {}

  public record Bind(
      @NotNull List<String> knowledge_base_ids, long revision, boolean allow_degraded) {}

  public record Quota(@Min(0) long limit, @NotBlank String reason) {}

  @PostMapping("/bootstrap")
  @Transactional
  public Map<String, Object> bootstrap(
      @RequestHeader("X-Bootstrap-Token") String token, @RequestBody @Valid Named input) {
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

  @GetMapping("/me")
  public Object me(@RequestAttribute Actor actor) {
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

  @GetMapping("/applications")
  public Object apps(@RequestAttribute Actor actor) {
    auth.developer(actor);
    return db.list("SELECT * FROM applications WHERE tenant_id=?", actor.tenant());
  }

  @PostMapping("/applications")
  @Transactional
  public Object app(@RequestAttribute Actor actor, @RequestBody @Valid Named body) {
    auth.developer(actor);
    String app = id();
    db.exec(
        "INSERT INTO applications(id,tenant_id,name,description) VALUES(?,?,?,?)",
        app,
        actor.tenant(),
        body.name(),
        Objects.toString(body.description(), ""));
    auth.audit(actor, "APP_CREATE", app, "");
    return Map.of("id", app);
  }

  @GetMapping("/applications/{id}/bindings")
  public Object bindings(@RequestAttribute Actor actor, @PathVariable String id) {
    auth.developer(actor);
    appOwned(actor, id);
    return db.list(
        "SELECT kb_id FROM application_bindings WHERE tenant_id=? AND application_id=?",
        actor.tenant(),
        id);
  }

  private void appOwned(Actor actor, String id) {
    db.one("SELECT id FROM applications WHERE tenant_id=? AND id=?", actor.tenant(), id);
  }

  @PutMapping("/applications/{id}/publication")
  @Transactional
  public void bind(
      @RequestAttribute Actor actor, @PathVariable String id, @RequestBody @Valid Bind body) {
    auth.developer(actor);
    auth.lock(actor);
    appOwned(actor, id);
    if (body.knowledge_base_ids().isEmpty() || body.knowledge_base_ids().size() > 20)
      throw new IllegalArgumentException();
    for (String kb : body.knowledge_base_ids()) {
      auth.kb(actor, kb, "read");
      if (!auth.granted(new Actor(actor.tenant(), id, "APP", "APPLICATION"), kb, "read"))
        throw new ApiException(409, "APP_NOT_AUTHORIZED", "请先在知识库权限中授予此应用读取权限");
    }
    if (db.exec(
            "UPDATE applications SET revision=revision+1,published=TRUE,allow_degraded=? WHERE tenant_id=? AND id=? AND revision=?",
            body.allow_degraded(),
            actor.tenant(),
            id,
            body.revision())
        != 1) throw ApiException.conflict();
    db.exec(
        "DELETE FROM application_bindings WHERE tenant_id=? AND application_id=?",
        actor.tenant(),
        id);
    for (String kb : new HashSet<>(body.knowledge_base_ids()))
      db.exec(
          "INSERT INTO application_bindings(tenant_id,application_id,kb_id) VALUES(?,?,?)",
          actor.tenant(),
          id,
          kb);
    try {
      db.exec(
          "INSERT INTO application_configurations(id,tenant_id,application_id,config_json,actor_id,state) VALUES(?,?,?,?,?,'PUBLISHED')",
          Db.id(),
          actor.tenant(),
          id,
          new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body),
          actor.subject());
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid application configuration");
    }
    auth.audit(actor, "APP_PUBLISH", id, "");
  }

  @GetMapping("/credentials")
  public Object keys(@RequestAttribute Actor actor) {
    auth.admin(actor);
    return db.list(
        "SELECT id,subject_id,kind,active,expires_at,scopes FROM credentials WHERE tenant_id=?",
        actor.tenant());
  }

  @DeleteMapping("/credentials/{id}")
  @Transactional
  public void revoke(@RequestAttribute Actor actor, @PathVariable String id) {
    auth.admin(actor);
    auth.lock(actor);
    if (db.exec(
            "UPDATE credentials SET active=FALSE WHERE tenant_id=? AND id=?", actor.tenant(), id)
        != 1) throw ApiException.hidden();
    auth.audit(actor, "CREDENTIAL_REVOKE", id, "");
  }

  @GetMapping("/usage")
  public Object usage(@RequestAttribute Actor actor) {
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

  @PutMapping("/quota")
  @Transactional
  public void quota(@RequestAttribute Actor actor, @RequestBody @Valid Quota body) {
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

  @GetMapping("/audit")
  public Object audit(@RequestAttribute Actor actor) {
    auth.admin(actor);
    return db.list(
        "SELECT * FROM audit_events WHERE tenant_id=? ORDER BY created_at DESC LIMIT 200",
        actor.tenant());
  }

  @PostMapping("/applications/{id}/configurations")
  @Transactional
  public Object saveConfiguration(
      @RequestAttribute Actor actor, @PathVariable String id, @RequestBody @Valid Bind body)
      throws Exception {
    auth.developer(actor);
    appOwned(actor, id);
    if (body.knowledge_base_ids().isEmpty() || body.knowledge_base_ids().size() > 20)
      throw new IllegalArgumentException();
    for (String kb : body.knowledge_base_ids()) auth.kb(actor, kb, "read");
    String configuration = Db.id();
    db.exec(
        "INSERT INTO application_configurations(id,tenant_id,application_id,config_json,actor_id,state) VALUES(?,?,?,?,?,'DRAFT')",
        configuration,
        actor.tenant(),
        id,
        new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body),
        actor.subject());
    auth.audit(actor, "APP_DRAFT_SAVE", id, configuration);
    return Map.of("id", configuration);
  }

  @GetMapping("/applications/{id}/configurations")
  public Object configurations(@RequestAttribute Actor actor, @PathVariable String id) {
    auth.developer(actor);
    appOwned(actor, id);
    return db.list(
        "SELECT * FROM application_configurations WHERE tenant_id=? AND application_id=? ORDER BY created_at DESC LIMIT 100",
        actor.tenant(),
        id);
  }

  public record ConfigurationPublish(@NotBlank String configuration_id, long revision) {}

  @PostMapping("/applications/{id}/configuration-publications")
  @Transactional
  public void publishConfiguration(
      @RequestAttribute Actor actor,
      @PathVariable String id,
      @RequestBody @Valid ConfigurationPublish body)
      throws Exception {
    auth.developer(actor);
    auth.lock(actor);
    appOwned(actor, id);
    var config =
        db.one(
            "SELECT * FROM application_configurations WHERE tenant_id=? AND application_id=? AND id=?",
            actor.tenant(),
            id,
            body.configuration_id());
    Bind saved =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readValue(str(config, "config_json"), Bind.class);
    // bind validates CURRENT knowledge grants; rollback cannot revive a revoked app grant.
    bind(actor, id, new Bind(saved.knowledge_base_ids(), body.revision(), saved.allow_degraded()));
    db.exec(
        "UPDATE application_configurations SET state='PUBLISHED' WHERE tenant_id=? AND id=?",
        actor.tenant(),
        body.configuration_id());
  }
}
