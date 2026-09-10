package com.careflow.platform;

import static com.careflow.platform.Db.*;
import static com.careflow.platform.KnowledgeConfiguration.*;

import com.careflow.platform.Identity.Actor;
import com.careflow.platform.ModelSnapshotService.StoredModel;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeConfigurationService {
  private final Identity auth;
  private final KnowledgeConfigurationRepository repository;
  private final ModelSnapshotService models;
  private final ObjectMapper json;

  public KnowledgeConfigurationService(
      Identity auth,
      KnowledgeConfigurationRepository repository,
      ModelSnapshotService models,
      ObjectMapper json) {
    this.auth = auth;
    this.repository = repository;
    this.models = models;
    this.json = json;
  }

  @io.swagger.v3.oas.annotations.media.Schema(name = "KnowledgeConfigurationView")
  public record View(
      String id,
      Definition definition,
      Map<String, Long> model_revisions,
      boolean ever_published,
      String created_at) {}

  private <T> T decode(String source, Class<T> type) {
    try {
      return json.readValue(source, type);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored knowledge configuration");
    }
  }

  private Map<String, StoredModel> snapshots(Map<String, Object> row) {
    try {
      return json.readValue(
          str(row, "models_json"), new TypeReference<Map<String, StoredModel>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored model snapshot");
    }
  }

  public Definition definition(String tenant, String id) {
    return decode(str(repository.get(tenant, id), "definition_json"), Definition.class);
  }

  private View view(Map<String, Object> row) {
    Map<String, Long> revisions = new TreeMap<>();
    snapshots(row)
        .forEach((kind, model) -> revisions.put(model.profile_id(), model.profile_revision()));
    return new View(
        str(row, "id"),
        decode(str(row, "definition_json"), Definition.class),
        revisions,
        bool(row, "ever_published"),
        str(row, "created_at"));
  }

  public Object list(Actor actor, String kb) {
    var base = auth.kb(actor, kb, "manage");
    return Map.of(
        "published_configuration",
        str(base, "published_configuration"),
        "revision",
        num(base, "configuration_revision"),
        "versions",
        repository.list(actor.tenant(), kb).stream().map(this::view).toList());
  }

  @Transactional
  public View create(Actor actor, String kb, Definition definition) {
    auth.lock(actor);
    auth.kb(actor, kb, "manage");
    Map<String, StoredModel> models = new TreeMap<>();
    models.put(
        "EMBEDDING",
        snapshot(
            actor.tenant(),
            definition.models().embedding_profile_id(),
            "EMBEDDING",
            definition.models().embedding_profile_revision()));
    models.put(
        "RERANK",
        snapshot(
            actor.tenant(),
            definition.models().rerank_profile_id(),
            "RERANK",
            definition.models().rerank_profile_revision()));
    models.put(
        "GENERATION",
        snapshot(
            actor.tenant(),
            definition.models().generation_profile_id(),
            "GENERATION",
            definition.models().generation_profile_revision()));
    String id = id();
    try {
      repository.create(
          actor.tenant(),
          kb,
          id,
          json.writeValueAsString(definition),
          json.writeValueAsString(models),
          actor.subject());
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException();
    }
    auth.audit(actor, "KB_CONFIGURATION_CREATE", kb, id);
    return view(repository.get(actor.tenant(), id));
  }

  private StoredModel snapshot(String tenant, String id, String kind, long expectedRevision) {
    return models.capture(tenant, id, kind, expectedRevision);
  }

  public RuntimeConfiguration runtime(String tenant, String id) {
    if (id == null || id.isBlank()) return null;
    var row = repository.get(tenant, id);
    Definition definition = decode(str(row, "definition_json"), Definition.class);
    var models = snapshots(row);
    return new RuntimeConfiguration(
        id,
        definition.parsing(),
        definition.chunking(),
        resolve(tenant, models.get("EMBEDDING")),
        resolve(tenant, models.get("RERANK")),
        resolve(tenant, models.get("GENERATION")));
  }

  private WorkerProtocolV1.ModelConfiguration resolve(String tenant, StoredModel model) {
    return models.resolve(tenant, model);
  }

  public String modelIdentity(WorkerProtocolV1.ModelConfiguration model) {
    if (model == null) return "";
    Map<String, String> values = new TreeMap<>();
    values.put("EMBEDDING_BASE_URL", model.base_url());
    values.put("EMBEDDING_MODEL", model.model());
    values.put("EMBEDDING_REVISION", model.revision());
    values.put("EMBEDDING_DIMENSIONS", String.valueOf(model.dimensions()));
    values.put("metric", "COSINE");
    try {
      List<String> entries = new ArrayList<>();
      for (var entry : values.entrySet())
        entries.add(
            json.writeValueAsString(entry.getKey())
                + ": "
                + json.writeValueAsString(entry.getValue()));
      return Identity.hash("{" + String.join(", ", entries) + "}");
    } catch (Exception e) {
      throw new IllegalStateException("Invalid model identity");
    }
  }

  private Map<String, Object> target(Actor actor, String kb, String id) {
    auth.kb(actor, kb, "manage");
    var row = repository.get(actor.tenant(), id);
    if (!kb.equals(str(row, "kb_id"))) throw ApiException.hidden();
    return row;
  }

  public Object impact(Actor actor, String kb, String id) {
    var next = decode(str(target(actor, kb, id), "definition_json"), Definition.class);
    var base = auth.kb(actor, kb, "manage");
    String previous = str(base, "published_configuration");
    Definition old = previous.isBlank() ? null : definition(actor.tenant(), previous);
    boolean reparse =
        old == null
            || !old.parsing().equals(next.parsing())
            || !old.chunking().equals(next.chunking());
    boolean embeddingChanged =
        previous.isBlank()
            || !embeddingIdentity(actor.tenant(), previous)
                .equals(embeddingIdentity(actor.tenant(), id));
    return Map.of(
        "configuration_id",
        id,
        "revision",
        num(base, "configuration_revision"),
        "reparse_required",
        reparse,
        "reindex_required",
        reparse || embeddingChanged,
        "existing_versions_unchanged",
        true,
        "applies_to_new_processing",
        true,
        "model_snapshot_changed",
        previous.isBlank()
            || !str(repository.get(actor.tenant(), previous), "models_json")
                .equals(str(repository.get(actor.tenant(), id), "models_json")));
  }

  private String embeddingIdentity(String tenant, String id) {
    var model = snapshots(repository.get(tenant, id)).get("EMBEDDING");
    return modelIdentity(
        new WorkerProtocolV1.ModelConfiguration(
            model.kind(),
            model.base_url(),
            model.model(),
            model.model_revision(),
            model.dimensions(),
            ""));
  }

  @Transactional
  public Object publish(Actor actor, String kb, Publish input) {
    auth.lock(actor);
    var base = auth.kb(actor, kb, "publish");
    target(actor, kb, input.configuration_id());
    if (!str(base, "status").equals("ACTIVE")) throw ApiException.hidden();
    runtime(
        actor.tenant(),
        input.configuration_id()); // Revalidate endpoint policy and stored credentials.
    repository.publish(
        actor.tenant(),
        kb,
        input.configuration_id(),
        str(base, "published_configuration"),
        input.revision(),
        actor.subject());
    auth.audit(
        actor,
        "KB_CONFIGURATION_PUBLISH",
        kb,
        "configuration=" + input.configuration_id() + ",reason=" + input.reason());
    return list(actor, kb);
  }
}
