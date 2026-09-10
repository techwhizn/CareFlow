package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Versioned evaluation inputs; reviews are explicit and never inferred from generated labels. */
@Service
public class EvaluationDatasets {
  private final Db db;
  private final Identity auth;
  private final ObjectMapper json;

  public EvaluationDatasets(Db db, Identity auth, ObjectMapper json) {
    this.db = db;
    this.auth = auth;
    this.json = json;
  }

  public record Create(@NotNull UUID knowledge_base_id, @NotBlank @Size(max = 200) String name) {}

  public record Case(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,100}") String id,
      @NotBlank @Size(max = 4000) String question,
      @NotBlank @Size(max = 8000) String reference_answer,
      @NotBlank @Pattern(regexp = "DIRECT|PARAPHRASE|IDENTIFIER|UNANSWERABLE|VERSION")
          String category,
      @NotBlank @Pattern(regexp = "ANSWERABLE|UNANSWERABLE|CONFLICT") String answerability,
      @NotNull @Size(max = 30) List<@NotNull UUID> expected_chunk_ids,
      @NotBlank @Pattern(regexp = "MEMBER|APP") String test_subject_kind,
      @NotNull UUID test_subject_id) {}

  public record Version(
      @Min(0) long revision, @NotEmpty @Size(max = 500) List<@NotNull @Valid Case> cases) {}

  public record Review(
      @NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision,
      @NotNull @Size(max = 2000) String note) {}

  private Map<String, Object> access(Actor actor, String id) {
    if (actor.app() || actor.role().equals("OPS")) throw ApiException.hidden();
    var row =
        db.one("SELECT * FROM evaluation_datasets WHERE tenant_id=? AND id=?", actor.tenant(), id);
    auth.kb(actor, str(row, "kb_id"), "edit");
    return row;
  }

  public Object list(Actor actor) {
    if (actor.app() || actor.role().equals("OPS")) throw ApiException.hidden();
    return db
        .list(
            "SELECT * FROM evaluation_datasets WHERE tenant_id=? ORDER BY created_at DESC,id DESC",
            actor.tenant())
        .stream()
        .filter(
            row -> {
              try {
                access(actor, str(row, "id"));
                return true;
              } catch (ApiException e) {
                if (e.status != 404) throw e;
                return false;
              }
            })
        .toList();
  }

  @Transactional
  public Object create(Actor actor, Create input) {
    auth.lock(actor);
    if (actor.app() || actor.role().equals("OPS")) throw ApiException.hidden();
    auth.kb(actor, input.knowledge_base_id().toString(), "edit");
    String id = id();
    db.exec(
        "INSERT INTO evaluation_datasets(id,tenant_id,kb_id,name,created_by) VALUES(?,?,?,?,?)",
        id,
        actor.tenant(),
        input.knowledge_base_id().toString(),
        input.name(),
        actor.subject());
    auth.audit(actor, "EVAL_DATASET_CREATE", id, "");
    return detail(actor, id);
  }

  public Object detail(Actor actor, String id) {
    var row = access(actor, id);
    row.put(
        "versions",
        db.list(
            "SELECT id,version_number,created_by,created_at FROM evaluation_dataset_versions WHERE tenant_id=? AND dataset_id=? ORDER BY version_number DESC",
            actor.tenant(),
            id));
    return row;
  }

  private List<Case> cases(Map<String, Object> row) {
    try {
      return json.readValue(str(row, "cases_json"), new TypeReference<List<Case>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored evaluation dataset");
    }
  }

  public Map<String, Object> version(Actor actor, String id, String version) {
    access(actor, id);
    var row =
        db.one(
            "SELECT * FROM evaluation_dataset_versions WHERE tenant_id=? AND dataset_id=? AND id=?",
            actor.tenant(),
            id,
            version);
    for (var source :
        db.list("SELECT source_version_id FROM evaluation_evidence WHERE version_id=?", version))
      auth.version(actor, str(source, "source_version_id"), "read");
    row.put("cases", cases(row));
    row.remove("cases_json");
    row.put(
        "reviews",
        db.list(
            "SELECT case_id,reviewer_id,decision,note,created_at FROM evaluation_case_reviews WHERE version_id=? ORDER BY case_id",
            version));
    return row;
  }

  private void validate(Actor actor, String kb, Case item) {
    if (item.test_subject_kind().equals("MEMBER"))
      db.one(
          "SELECT id FROM members WHERE tenant_id=? AND id=? AND active=TRUE AND removed=FALSE",
          actor.tenant(),
          item.test_subject_id().toString());
    else
      db.one(
          "SELECT id FROM applications WHERE tenant_id=? AND id=?",
          actor.tenant(),
          item.test_subject_id().toString());
    for (UUID chunk : item.expected_chunk_ids()) {
      var source =
          db.one(
              "SELECT c.version_id,d.kb_id FROM chunks c JOIN document_versions v ON v.id=c.version_id JOIN documents d ON d.id=v.document_id WHERE c.tenant_id=? AND c.id=?",
              actor.tenant(),
              chunk.toString());
      if (!kb.equals(str(source, "kb_id"))) throw ApiException.hidden();
      auth.version(actor, str(source, "version_id"), "read");
    }
  }

  @Transactional
  public Object save(Actor actor, String id, Version input) {
    auth.lock(actor);
    var dataset = access(actor, id);
    Set<String> ids = new HashSet<>();
    for (var item : input.cases()) {
      if (!ids.add(item.id())) throw new IllegalArgumentException("Duplicate case id");
      validate(actor, str(dataset, "kb_id"), item);
    }
    if (db.exec(
            "UPDATE evaluation_datasets SET revision=revision+1 WHERE tenant_id=? AND id=? AND revision=?",
            actor.tenant(),
            id,
            input.revision())
        != 1) throw ApiException.conflict();
    String version = id();
    String data;
    try {
      data = json.writeValueAsString(input.cases());
    } catch (Exception e) {
      throw new IllegalArgumentException();
    }
    db.exec(
        "INSERT INTO evaluation_dataset_versions(id,tenant_id,dataset_id,version_number,cases_json,created_by) VALUES(?,?,?,?,?,?)",
        version,
        actor.tenant(),
        id,
        input.revision() + 1,
        data,
        actor.subject());
    var chunks = new HashSet<UUID>();
    for (var item : input.cases()) chunks.addAll(item.expected_chunk_ids());
    for (var chunk : chunks)
      db.exec(
          "INSERT INTO evaluation_evidence(version_id,document_id,source_version_id,chunk_id) SELECT ?,v.document_id,c.version_id,c.id FROM chunks c JOIN document_versions v ON v.id=c.version_id WHERE c.tenant_id=? AND c.id=?",
          version,
          actor.tenant(),
          chunk.toString());
    auth.audit(actor, "EVAL_VERSION_CREATE", id, version);
    return version(actor, id, version);
  }

  @Transactional
  public Object review(Actor actor, String dataset, String version, String caseId, Review input) {
    auth.lock(actor);
    var row = version(actor, dataset, version);
    @SuppressWarnings("unchecked")
    var cases = (List<Case>) row.get("cases");
    var item =
        cases.stream()
            .filter(c -> c.id().equals(caseId))
            .findFirst()
            .orElseThrow(ApiException::hidden);
    if (input.decision().equals("APPROVED")
        && !item.answerability().equals("UNANSWERABLE")
        && item.expected_chunk_ids().isEmpty())
      throw new ApiException(409, "EVIDENCE_REQUIRED", "通过评审前需补齐正确证据");
    // First decision is immutable. Corrections create a new dataset version and require fresh
    // review.
    if (!db.list(
            "SELECT case_id FROM evaluation_case_reviews WHERE version_id=? AND case_id=?",
            version,
            caseId)
        .isEmpty()) throw ApiException.conflict();
    db.exec(
        "INSERT INTO evaluation_case_reviews(version_id,case_id,reviewer_id,decision,note) VALUES(?,?,?,?,?)",
        version,
        caseId,
        actor.subject(),
        input.decision(),
        input.note());
    auth.audit(
        actor,
        "EVAL_CASE_REVIEW",
        dataset,
        "version=" + version + ",case=" + caseId + ",decision=" + input.decision());
    return version(actor, dataset, version);
  }
}
