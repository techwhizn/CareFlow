package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import com.careflow.platform.RetrievalService.Scope;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImprovementService {
  public record Create(
      @NotNull @Pattern(regexp = "ANSWER|NO_RESULT") String source_kind,
      @NotNull UUID source_id,
      @NotNull UUID knowledge_base_id,
      @Size(max = 2000) String description) {}

  public record Update(
      @Min(0) long revision,
      @NotNull @Pattern(regexp = "OPEN|IN_PROGRESS|RESOLVED|DISMISSED") String state,
      UUID assignee_id,
      @Size(max = 2000) String resolution) {}

  private final Db db;
  private final Identity auth;
  private final AnswerHistoryService history;
  private final QueryRecordService records;
  private final EvidenceAuthorization evidence;

  public ImprovementService(
      Db db,
      Identity auth,
      AnswerHistoryService history,
      QueryRecordService records,
      EvidenceAuthorization evidence) {
    this.db = db;
    this.auth = auth;
    this.history = history;
    this.records = records;
    this.evidence = evidence;
  }

  private Map<String, Object> activeBase(Actor actor, String kb, String action) {
    var row = auth.kb(actor, kb, action);
    if (!"ACTIVE".equals(str(row, "status"))) throw ApiException.hidden();
    return row;
  }

  @Transactional
  public Map<String, Object> create(Actor actor, Create input) {
    auth.lock(actor);
    String kb = input.knowledge_base_id().toString(), source = input.source_id().toString();
    activeBase(actor, kb, "read");
    String answerId = null, recordId, reason;
    if (input.source_kind().equals("ANSWER")) {
      var answer = history.answer(actor, source);
      if (!"incorrect".equals(str(answer, "feedback")))
        throw new ApiException(409, "FEEDBACK_REQUIRED", "请先记录差评及原因");
      answerId = source;
      recordId = str(answer, "query_record_id");
      reason = str(answer, "feedback_reason");
      if (reason.isBlank()) reason = "OTHER";
    } else {
      var record = records.owned(actor, source);
      if (!Set.of("NO_MATCH", "BELOW_THRESHOLD").contains(str(record, "evidence_status")))
        throw new ApiException(409, "NO_RESULT_REQUIRED", "仅无结果查询可转为此类改进任务");
      recordId = source;
      reason = "MISSING_KNOWLEDGE";
    }
    if (recordId.isBlank())
      throw new ApiException(409, "QUERY_RECORD_UNAVAILABLE", "旧答案缺少查询记录，请重新提问后提交");
    var record = records.owned(actor, recordId);
    // Explicit KB scopes cannot be silently redirected to an unrelated knowledge base.
    String scope = str(record, "knowledge_base_ids");
    if (!scope.equals("[]") && !scope.contains("\"" + kb + "\"")) throw ApiException.hidden();
    String app = str(record, "application_id");
    if (!app.isBlank())
      db.one(
          "SELECT kb_id FROM application_bindings WHERE tenant_id=? AND application_id=? AND kb_id=?",
          actor.tenant(),
          app,
          kb);
    var previous =
        db.list(
            "SELECT id FROM improvement_tasks WHERE tenant_id=? AND kb_id=? AND source_kind=? AND source_id=?",
            actor.tenant(),
            kb,
            input.source_kind(),
            source);
    if (!previous.isEmpty()) return get(actor, str(previous.getFirst(), "id"));
    String task = id();
    db.exec(
        "INSERT INTO improvement_tasks(id,tenant_id,kb_id,creator_id,creator_kind,source_kind,source_id,answer_id,query_record_id,reason,description) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        task,
        actor.tenant(),
        kb,
        actor.subject(),
        actor.kind(),
        input.source_kind(),
        source,
        answerId,
        recordId,
        reason,
        Objects.toString(input.description(), "").strip());
    auth.audit(actor, "IMPROVEMENT_CREATED", task, input.source_kind());
    return get(actor, task);
  }

  private Map<String, Object> authorized(Actor actor, String id, boolean edit) {
    var task =
        db.one(
            "SELECT * FROM improvement_tasks WHERE tenant_id=? AND id=?",
            actor.tenant(),
            UUID.fromString(id).toString());
    boolean creator =
        actor.subject().equals(str(task, "creator_id"))
            && actor.kind().equals(str(task, "creator_kind"));
    activeBase(actor, str(task, "kb_id"), edit || !creator ? "edit" : "read");
    var record =
        db.one(
            "SELECT * FROM query_records WHERE tenant_id=? AND id=?",
            actor.tenant(),
            str(task, "query_record_id"));
    records.validate(actor, record);
    if (!str(task, "answer_id").isBlank()) {
      var answer =
          db.one(
              "SELECT * FROM answers WHERE tenant_id=? AND id=?",
              actor.tenant(),
              str(task, "answer_id"));
      for (var dependency : history.dependencies(str(answer, "id")))
        evidence.check(
            actor, dependency, new Scope(List.of(), false, str(answer, "application_id"), -1));
    }
    return task;
  }

  public Map<String, Object> get(Actor actor, String id) {
    var task = authorized(actor, id, false);
    var result = new LinkedHashMap<>(task);
    boolean canManage;
    try {
      activeBase(actor, str(task, "kb_id"), "edit");
      canManage = true;
    } catch (ApiException error) {
      if (error.status != 404) throw error;
      canManage = false;
    }
    result.put("can_manage", canManage);
    result.put(
        "query",
        db.one(
            "SELECT * FROM query_records WHERE tenant_id=? AND id=?",
            actor.tenant(),
            str(task, "query_record_id")));
    if (!str(task, "answer_id").isBlank()) {
      result.put(
          "answer",
          db.one(
              "SELECT question,content,feedback,feedback_reason,feedback_comment FROM answers WHERE tenant_id=? AND id=?",
              actor.tenant(),
              str(task, "answer_id")));
      result.put(
          "evidence",
          db.list(
              "SELECT document_id,version_id,chunk_id,evidence_role FROM answer_evidence WHERE answer_id=?",
              str(task, "answer_id")));
    }
    return result;
  }

  public List<Map<String, Object>> list(Actor actor) {
    return db
        .list(
            "SELECT * FROM improvement_tasks WHERE tenant_id=? ORDER BY updated_at DESC,id DESC LIMIT 100",
            actor.tenant())
        .stream()
        .filter(
            row -> {
              try {
                authorized(actor, str(row, "id"), false);
                return true;
              } catch (ApiException e) {
                if (e.status != 404) throw e;
                return false;
              }
            })
        .toList();
  }

  public List<Map<String, Object>> assignees(Actor actor, String id) {
    var task = authorized(actor, id, true);
    return db
        .list(
            "SELECT id,name,role FROM members WHERE tenant_id=? AND active=TRUE AND removed=FALSE AND role IN ('OWNER','ADMIN','KNOWLEDGE_MANAGER')",
            actor.tenant())
        .stream()
        .filter(
            row -> {
              try {
                authorized(
                    new Actor(actor.tenant(), str(row, "id"), "MEMBER", str(row, "role")),
                    str(task, "id"),
                    true);
                return true;
              } catch (ApiException error) {
                if (error.status != 404) throw error;
                return false;
              }
            })
        .toList();
  }

  @Transactional
  public Map<String, Object> update(Actor actor, String id, Update input) {
    auth.lock(actor);
    var task = authorized(actor, id, true);
    if (num(task, "revision") != input.revision()) throw ApiException.conflict();
    String resolution = Objects.toString(input.resolution(), "").strip();
    if (Set.of("RESOLVED", "DISMISSED").contains(input.state()) && resolution.isBlank())
      throw new IllegalArgumentException("Closing a task requires a resolution");
    String assignee = input.assignee_id() == null ? null : input.assignee_id().toString();
    if (assignee != null) {
      var member =
          db.one(
              "SELECT role FROM members WHERE tenant_id=? AND id=? AND active=TRUE AND removed=FALSE",
              actor.tenant(),
              assignee);
      authorized(
          new Actor(actor.tenant(), assignee, "MEMBER", str(member, "role")),
          str(task, "id"),
          true);
    }
    db.exec(
        "UPDATE improvement_tasks SET state=?,assignee_id=?,resolution=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",
        input.state(),
        assignee,
        resolution,
        actor.tenant(),
        id);
    auth.audit(actor, "IMPROVEMENT_UPDATED", id, input.state());
    return get(actor, id);
  }
}
