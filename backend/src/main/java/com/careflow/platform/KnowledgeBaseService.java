package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeBaseService {
  public record Create(
      @NotBlank @Size(max = 200) String name, @Size(max = 2000) String description) {}

  public record Attributes(
      @NotBlank @Size(max = 200) String name,
      @Size(max = 2000) String description,
      @NotNull @Pattern(regexp = "[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*") @Size(max = 20)
          String language,
      @NotNull @Size(max = 20) List<@NotBlank @Size(max = 50) String> tags,
      @NotBlank String owner_id,
      @Min(0) long revision) {}

  public record Overview(
      long document_count,
      long effective_chunk_count,
      Long failed_job_count,
      long known_source_bytes,
      long unknown_source_objects,
      List<Map<String, Object>> applications,
      boolean applications_visible) {}

  private final KnowledgeBaseRepository repository;
  private final Identity auth;
  private final ObjectMapper json;
  private final EntitlementService entitlements;

  public KnowledgeBaseService(
      KnowledgeBaseRepository repository,
      Identity auth,
      ObjectMapper json,
      EntitlementService entitlements) {
    this.repository = repository;
    this.auth = auth;
    this.json = json;
    this.entitlements = entitlements;
  }

  private boolean readable(Actor actor, String id) {
    try {
      auth.kb(actor, id, "read");
      return true;
    } catch (ApiException e) {
      if (e.status != 404) throw e;
      return false;
    }
  }

  public List<Map<String, Object>> list(Actor actor) {
    return repository.list(actor.tenant()).stream()
        .filter(k -> readable(actor, str(k, "id")))
        .map(this::view)
        .toList();
  }

  public Map<String, Object> get(Actor actor, String id) {
    return view(auth.kb(actor, id, "read"));
  }

  private Map<String, Object> view(Map<String, Object> row) {
    var result = new LinkedHashMap<>(row);
    try {
      result.put(
          "tags",
          row.get("tags_json") == null
              ? List.of()
              : json.readValue(str(row, "tags_json"), new TypeReference<List<String>>() {}));
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored knowledge tags");
    }
    result.remove("tags_json");
    return result;
  }

  @Transactional
  public Map<String, Object> create(Actor actor, Create input) {
    auth.manager(actor);
    auth.lock(actor);
    entitlements.newKnowledgeBase(actor.tenant());
    String id = id();
    repository.create(
        actor.tenant(),
        id,
        input.name().trim(),
        Objects.toString(input.description(), ""),
        actor.subject());
    auth.audit(actor, "KB_CREATE", id, "");
    return get(actor, id);
  }

  @Transactional
  public Map<String, Object> settings(Actor actor, String id) {
    auth.lock(actor);
    var row = auth.kb(actor, id, "manage");
    return Map.of("knowledge_base", view(row), "owners", repository.owners(actor.tenant()));
  }

  @Transactional
  public Map<String, Object> update(Actor actor, String id, Attributes input) {
    auth.lock(actor);
    var before = auth.kb(actor, id, "manage");
    if (repository.owners(actor.tenant()).stream()
        .noneMatch(m -> str(m, "id").equals(input.owner_id())))
      throw new ApiException(400, "INVALID_OWNER", "责任人必须是本企业启用的知识管理员或管理员");
    try {
      String tags =
          json.writeValueAsString(input.tags().stream().map(String::trim).distinct().toList());
      if (repository.update(actor.tenant(), id, input, tags) != 1) throw ApiException.conflict();
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException();
    }
    auth.audit(
        actor,
        "KB_ATTRIBUTES_UPDATE",
        id,
        "previous_owner=" + str(before, "owner_id") + ",owner=" + input.owner_id());
    // Transfer may remove the caller's implicit read grant. Return only the revision
    // acknowledgement.
    return Map.of("id", id, "revision", input.revision() + 1);
  }

  @Transactional
  public Overview overview(Actor actor, String id) {
    auth.lock(actor);
    auth.kb(actor, id, "read");
    long docs = 0, chunks = 0, failures = 0, bytes = 0, unknown = 0;
    boolean manage = false;
    try {
      auth.kb(actor, id, "manage");
      manage = true;
    } catch (ApiException e) {
      if (e.status != 404) throw e;
    }
    Set<String> objects = new HashSet<>();
    for (var d : repository.documents(actor.tenant(), id)) {
      String document = str(d, "id");
      try {
        auth.document(actor, document, "read");
      } catch (ApiException e) {
        if (e.status != 404) throw e;
        continue;
      }
      docs++;
      for (var v : repository.versions(actor.tenant(), document)) {
        String version = str(v, "id");
        try {
          auth.version(actor, version, "read");
        } catch (ApiException e) {
          if (e.status != 404) throw e;
          continue;
        }
        if (version.equals(str(d, "published_version")))
          chunks += repository.effectiveChunks(actor.tenant(), version);
        if (objects.add(str(v, "object_key"))) {
          if (v.get("size_bytes") == null) unknown++;
          else bytes += num(v, "size_bytes");
        }
      }
      if (manage) {
        try {
          auth.document(actor, document, "edit");
          failures += repository.failedJobs(actor.tenant(), document);
        } catch (ApiException e) {
          if (e.status != 404) throw e;
        }
      }
    }
    boolean appVisibility =
        !actor.app() && Set.of("OWNER", "ADMIN", "DEVELOPER").contains(actor.role());
    return new Overview(
        docs,
        chunks,
        manage ? failures : null,
        bytes,
        unknown,
        appVisibility ? repository.applications(actor.tenant(), id) : List.of(),
        appVisibility);
  }
}
