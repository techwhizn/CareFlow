package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApplicationService {
  private final Db db;
  private final Identity auth;

  public ApplicationService(Db db, Identity auth) {
    this.db = db;
    this.auth = auth;
  }

  public record Named(
      @NotBlank @Size(max = 200) String name, @Size(max = 2000) String description) {}

  public record Bind(
      @NotNull List<String> knowledge_base_ids, long revision, boolean allow_degraded) {}

  public record ConfigurationPublish(@NotBlank String configuration_id, long revision) {}

  public Object apps(Actor actor) {
    auth.developer(actor);
    return db.list("SELECT * FROM applications WHERE tenant_id=?", actor.tenant());
  }

  @Transactional
  public Object app(Actor actor, Named body) {
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

  public Object bindings(Actor actor, String id) {
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

  @Transactional
  public void bind(Actor actor, String id, Bind body) {
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

  @Transactional
  public Object saveConfiguration(Actor actor, String id, Bind body) throws Exception {
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

  public Object configurations(Actor actor, String id) {
    auth.developer(actor);
    appOwned(actor, id);
    return db.list(
        "SELECT * FROM application_configurations WHERE tenant_id=? AND application_id=? ORDER BY created_at DESC LIMIT 100",
        actor.tenant(),
        id);
  }

  @Transactional
  public void publishConfiguration(Actor actor, String id, ConfigurationPublish body)
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
