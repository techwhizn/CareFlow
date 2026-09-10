package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeLifecycleService {
  public record StateChange(
      @NotNull @Pattern(regexp = "ACTIVE|ARCHIVED|DELETED") String status, @Min(0) long revision) {}

  public record Impact(
      String status,
      long revision,
      long visible_documents,
      long own_dependent_answers,
      List<Map<String, Object>> applications,
      boolean applications_visible,
      String scope_notice) {}

  private final Db db;
  private final Identity auth;
  private final KnowledgeBaseRepository repository;

  public KnowledgeLifecycleService(Db db, Identity auth, KnowledgeBaseRepository repository) {
    this.db = db;
    this.auth = auth;
    this.repository = repository;
  }

  @Transactional
  public Impact impact(Actor actor, String id) {
    auth.lock(actor);
    var kb = auth.kb(actor, id, "manage");
    long documents = 0;
    Set<String> visible = new HashSet<>();
    for (var document : repository.documents(actor.tenant(), id)) {
      try {
        auth.document(actor, str(document, "id"), "read");
        visible.add(str(document, "id"));
        documents++;
      } catch (ApiException e) {
        if (e.status != 404) throw e;
      }
    }
    Set<String> answers = new HashSet<>();
    for (var row :
        db.list(
            "SELECT a.id,e.document_id FROM answers a JOIN answer_evidence e ON e.answer_id=a.id WHERE a.tenant_id=? AND a.subject_id=?",
            actor.tenant(),
            actor.subject())) {
      if (visible.contains(str(row, "document_id"))) answers.add(str(row, "id"));
    }
    boolean applications =
        !actor.app() && Set.of("OWNER", "ADMIN", "DEVELOPER").contains(actor.role());
    return new Impact(
        str(kb, "status"),
        num(kb, "revision"),
        documents,
        answers.size(),
        applications ? repository.applications(actor.tenant(), id) : List.of(),
        applications,
        "文档计数只含当前可读资料，历史计数只含本人依赖答案；操作作用于整个知识库，可能影响其他成员与应用。归档可恢复，删除不可通过此接口恢复。");
  }

  @Transactional
  public Map<String, Object> change(Actor actor, String id, StateChange input) {
    auth.lock(actor);
    var kb = auth.kb(actor, id, "manage");
    if (num(kb, "revision") != input.revision()) throw ApiException.conflict();
    if (str(kb, "status").equals(input.status()))
      return Map.of("status", input.status(), "revision", input.revision());
    if (input.status().equals("ACTIVE")) {
      if (!str(kb, "status").equals("ARCHIVED")) throw ApiException.conflict();
      if (repository.owners(actor.tenant()).stream()
          .noneMatch(m -> str(m, "id").equals(str(kb, "owner_id"))))
        throw new ApiException(409, "RESOURCE_OWNER_REQUIRED", "恢复前请先指定启用的知识库责任人");
    }
    if (db.exec(
            "UPDATE knowledge_bases SET status=?,revision=revision+1 WHERE tenant_id=? AND id=? AND revision=?",
            input.status(),
            actor.tenant(),
            id,
            input.revision())
        != 1) throw ApiException.conflict();
    if (!input.status().equals("ACTIVE")) {
      db.exec(
          "UPDATE document_versions SET state='FAILED' WHERE tenant_id=? AND state IN ('QUEUED','PARSING','INDEXING') AND document_id IN (SELECT id FROM documents WHERE tenant_id=? AND kb_id=?)",
          actor.tenant(),
          actor.tenant(),
          id);
      db.exec(
          "UPDATE jobs SET state='CANCELLED',lease_token=NULL,lease_until=NULL,error_code='KNOWLEDGE_BASE_INACTIVE' WHERE tenant_id=? AND state IN ('QUEUED','RUNNING') AND version_id IN (SELECT v.id FROM document_versions v JOIN documents d ON d.id=v.document_id AND d.tenant_id=v.tenant_id WHERE d.tenant_id=? AND d.kb_id=?)",
          actor.tenant(),
          actor.tenant(),
          id);
    }
    String cleanup = "";
    if (input.status().equals("DELETED")) {
      cleanup = id();
      db.exec(
          "INSERT INTO cleanup_requests(id,tenant_id,resource_type,resource_id,requested_by) VALUES(?,?,'KNOWLEDGE_BASE',?,?)",
          cleanup,
          actor.tenant(),
          id,
          actor.subject());
    }
    db.exec("UPDATE tenants SET revision=revision+1 WHERE id=?", actor.tenant());
    auth.audit(
        actor, "KB_" + input.status(), id, cleanup.isEmpty() ? "" : "cleanup_request=" + cleanup);
    return Map.of(
        "status", input.status(), "revision", input.revision() + 1, "cleanup_request_id", cleanup);
  }
}
