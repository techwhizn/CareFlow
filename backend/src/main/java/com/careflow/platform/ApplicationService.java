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
  private final ApplicationConfigurationService configurations;

  public ApplicationService(Db db, Identity auth, ApplicationConfigurationService configurations) {
    this.db = db;
    this.auth = auth;
    this.configurations = configurations;
  }

  public record Named(
      @NotBlank @Size(max = 200) String name, @Size(max = 2000) String description, UUID owner_id) {
    public Named(String name, String description) {
      this(name, description, null);
    }
  }

  public record Bind(
      @NotNull @Size(min = 1, max = 20) List<@NotBlank String> knowledge_base_ids,
      @Min(0) long revision,
      boolean allow_degraded,
      UUID owner_id,
      @jakarta.validation.Valid KnowledgeConfiguration.Retrieval retrieval,
      @jakarta.validation.Valid ApplicationPolicy.Models models,
      @jakarta.validation.Valid ApplicationPolicy.Answer answer_policy) {
    public Bind {
      answer_policy = answer_policy == null ? ApplicationPolicy.Answer.defaults() : answer_policy;
    }

    public Bind(List<String> bases, long revision, boolean degraded) {
      this(bases, revision, degraded, null, null, null, null);
    }
  }

  public record ConfigurationPublish(@NotBlank String configuration_id, long revision) {}

  public Object owners(Actor actor) {
    auth.developer(actor);
    return db.list(
        "SELECT id,name FROM members WHERE tenant_id=? AND active=TRUE AND removed=FALSE",
        actor.tenant());
  }

  public Object available(Actor actor) {
    return db
        .list(
            "SELECT id,name,description FROM applications WHERE tenant_id=? AND published=TRUE",
            actor.tenant())
        .stream()
        .filter(
            app -> {
              if (actor.app() && !actor.subject().equals(str(app, "id"))) return false;
              for (var row :
                  db.list(
                      "SELECT kb_id FROM application_bindings WHERE tenant_id=? AND application_id=?",
                      actor.tenant(),
                      str(app, "id"))) {
                try {
                  if ("ACTIVE".equals(str(auth.kb(actor, str(row, "kb_id"), "read"), "status")))
                    return true;
                } catch (ApiException error) {
                  if (error.status != 404) throw error;
                }
              }
              return false;
            })
        .toList();
  }

  public Object apps(Actor actor) {
    auth.developer(actor);
    return db.list("SELECT * FROM applications WHERE tenant_id=?", actor.tenant());
  }

  @Transactional
  public Object app(Actor actor, Named body) {
    auth.developer(actor);
    String owner = body.owner_id() == null ? actor.subject() : body.owner_id().toString();
    db.one(
        "SELECT id FROM members WHERE tenant_id=? AND id=? AND active=TRUE AND removed=FALSE",
        actor.tenant(),
        owner);
    String app = id();
    db.exec(
        "INSERT INTO applications(id,tenant_id,name,description,owner_id) VALUES(?,?,?,?,?)",
        app,
        actor.tenant(),
        body.name(),
        Objects.toString(body.description(), ""),
        owner);
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

  public void bind(Actor actor, String id, Bind body) {
    configurations.bind(actor, id, body);
  }

  public Object saveConfiguration(Actor actor, String id, Bind body) {
    return configurations.create(actor, id, body);
  }

  public Object configurations(Actor actor, String id) {
    return configurations.list(actor, id);
  }

  public void publishConfiguration(Actor actor, String id, ConfigurationPublish body) {
    configurations.publish(actor, id, body);
  }

  public Object publications(Actor actor, String id) {
    return configurations.publications(actor, id);
  }
}
