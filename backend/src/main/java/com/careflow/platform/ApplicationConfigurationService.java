package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import com.careflow.platform.ModelSnapshotService.StoredModel;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApplicationConfigurationService {
  private final Db db;
  private final Identity auth;
  private final ModelSnapshotService models;
  private final ObjectMapper json;
  private final Validator validator;

  public ApplicationConfigurationService(
      Db db, Identity auth, ModelSnapshotService models, ObjectMapper json, Validator validator) {
    this.db = db;
    this.auth = auth;
    this.models = models;
    this.json = json;
    this.validator = validator;
  }

  private Map<String, Object> owned(Actor actor, String app) {
    auth.developer(actor);
    return db.one(
        "SELECT * FROM applications WHERE tenant_id=? AND id=?",
        actor.tenant(),
        UUID.fromString(app).toString());
  }

  private void validate(Actor actor, String app, ApplicationService.Bind body, boolean publishing) {
    if (!validator.validate(body).isEmpty()
        || body.knowledge_base_ids().isEmpty()
        || body.knowledge_base_ids().size() > 20)
      throw new IllegalArgumentException("Invalid application configuration");
    for (String kb : new HashSet<>(body.knowledge_base_ids())) {
      UUID.fromString(kb);
      var base = auth.kb(actor, kb, "read");
      if (!"ACTIVE".equals(str(base, "status"))) throw ApiException.hidden();
      if (publishing
          && !auth.granted(new Actor(actor.tenant(), app, "APP", "APPLICATION"), kb, "read"))
        throw new ApiException(409, "APP_NOT_AUTHORIZED", "请先授予应用知识库读取权限");
    }
    if (body.owner_id() != null)
      db.one(
          "SELECT id FROM members WHERE tenant_id=? AND id=? AND active=TRUE AND removed=FALSE",
          actor.tenant(),
          body.owner_id().toString());
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid application configuration");
    }
  }

  private ApplicationService.Bind definition(Map<String, Object> row) {
    try {
      return json.readValue(str(row, "config_json"), ApplicationService.Bind.class);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored application configuration");
    }
  }

  private Map<String, Object> view(Map<String, Object> row) {
    var output = new LinkedHashMap<>(row);
    output.remove("models_json");
    output.put("definition", definition(row));
    return output;
  }

  @Transactional
  public Map<String, Object> create(Actor actor, String app, ApplicationService.Bind body) {
    auth.lock(actor);
    owned(actor, app);
    validate(actor, app, body, false);
    var snapshots = new TreeMap<String, StoredModel>();
    if (body.models() != null) {
      var refs = body.models();
      snapshots.put(
          "RERANK",
          models.capture(
              actor.tenant(), refs.rerank_profile_id(), "RERANK", refs.rerank_profile_revision()));
      snapshots.put(
          "GENERATION",
          models.capture(
              actor.tenant(),
              refs.generation_profile_id(),
              "GENERATION",
              refs.generation_profile_revision()));
    }
    String id = id();
    db.exec(
        "INSERT INTO application_configurations(id,tenant_id,application_id,config_json,models_json,actor_id,state) VALUES(?,?,?,?,?,?,'DRAFT')",
        id,
        actor.tenant(),
        app,
        encode(body),
        encode(snapshots),
        actor.subject());
    auth.audit(actor, "APP_DRAFT_SAVE", app, id);
    return view(
        db.one(
            "SELECT * FROM application_configurations WHERE tenant_id=? AND id=?",
            actor.tenant(),
            id));
  }

  public List<Map<String, Object>> list(Actor actor, String app) {
    owned(actor, app);
    return db
        .list(
            "SELECT * FROM application_configurations WHERE tenant_id=? AND application_id=? ORDER BY created_at DESC,id DESC LIMIT 100",
            actor.tenant(),
            app)
        .stream()
        .map(this::view)
        .toList();
  }

  @Transactional
  public void bind(Actor actor, String app, ApplicationService.Bind body) {
    auth.lock(actor);
    var created = create(actor, app, body);
    publish(
        actor,
        app,
        new ApplicationService.ConfigurationPublish(str(created, "id"), body.revision()));
  }

  @Transactional
  public void publish(Actor actor, String app, ApplicationService.ConfigurationPublish input) {
    auth.lock(actor);
    var current = owned(actor, app);
    var row =
        db.one(
            "SELECT * FROM application_configurations WHERE tenant_id=? AND application_id=? AND id=?",
            actor.tenant(),
            app,
            UUID.fromString(input.configuration_id()).toString());
    var body = definition(row);
    validate(actor, app, body, true);
    resolve(actor.tenant(), row);
    String owner = body.owner_id() == null ? str(current, "owner_id") : body.owner_id().toString();
    if (owner.isBlank()) owner = actor.subject();
    db.one(
        "SELECT id FROM members WHERE tenant_id=? AND id=? AND active=TRUE AND removed=FALSE",
        actor.tenant(),
        owner);
    if (db.exec(
            "UPDATE applications SET revision=revision+1,published=TRUE,allow_degraded=?,owner_id=?,published_configuration=? WHERE tenant_id=? AND id=? AND revision=?",
            body.allow_degraded(),
            owner,
            str(row, "id"),
            actor.tenant(),
            app,
            input.revision())
        != 1) throw ApiException.conflict();
    db.exec(
        "DELETE FROM application_bindings WHERE tenant_id=? AND application_id=?",
        actor.tenant(),
        app);
    for (String kb : new HashSet<>(body.knowledge_base_ids()))
      db.exec(
          "INSERT INTO application_bindings(tenant_id,application_id,kb_id) VALUES(?,?,?)",
          actor.tenant(),
          app,
          kb);
    db.exec(
        "UPDATE application_configurations SET state='PUBLISHED' WHERE tenant_id=? AND id=?",
        actor.tenant(),
        str(row, "id"));
    db.exec(
        "INSERT INTO application_publications(id,tenant_id,application_id,configuration_id,previous_configuration_id,actor_id,revision) VALUES(?,?,?,?,?,?,?)",
        id(),
        actor.tenant(),
        app,
        str(row, "id"),
        current.get("published_configuration"),
        actor.subject(),
        input.revision() + 1);
    auth.audit(actor, "APP_PUBLISH", app, str(row, "id"));
  }

  public List<Map<String, Object>> publications(Actor actor, String app) {
    owned(actor, app);
    return db.list(
        "SELECT * FROM application_publications WHERE tenant_id=? AND application_id=? ORDER BY revision DESC LIMIT 100",
        actor.tenant(),
        app);
  }

  private ApplicationPolicy.Runtime resolve(String tenant, Map<String, Object> row) {
    var body = definition(row);
    KnowledgeConfiguration.RuntimeConfiguration runtime = null;
    if (body.models() != null) {
      Map<String, StoredModel> saved;
      try {
        saved =
            json.readValue(
                str(row, "models_json"), new TypeReference<Map<String, StoredModel>>() {});
      } catch (Exception error) {
        throw new IllegalStateException("Invalid application model snapshot");
      }
      runtime =
          new KnowledgeConfiguration.RuntimeConfiguration(
              str(row, "id"),
              null,
              null,
              null,
              models.resolve(tenant, saved.get("RERANK")),
              models.resolve(tenant, saved.get("GENERATION")));
    }
    var retrieval = body.retrieval();
    if (retrieval == null && runtime != null)
      retrieval = new KnowledgeConfiguration.Retrieval("hybrid", 6, null, body.allow_degraded());
    return new ApplicationPolicy.Runtime(str(row, "id"), retrieval, runtime, body.answer_policy());
  }

  public ApplicationPolicy.Runtime runtime(String tenant, String app) {
    var application =
        db.one(
            "SELECT published_configuration FROM applications WHERE tenant_id=? AND id=? AND published=TRUE",
            tenant,
            app);
    String id = str(application, "published_configuration");
    if (id.isBlank())
      return new ApplicationPolicy.Runtime("", null, null, ApplicationPolicy.Answer.defaults());
    return resolve(
        tenant,
        db.one(
            "SELECT * FROM application_configurations WHERE tenant_id=? AND application_id=? AND id=?",
            tenant,
            app,
            id));
  }
}
