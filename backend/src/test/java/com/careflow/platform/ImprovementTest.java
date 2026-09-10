package com.careflow.platform;

import static com.careflow.platform.Db.*;
import static org.assertj.core.api.Assertions.*;

import com.careflow.platform.Identity.Actor;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class ImprovementTest extends ContentTestSupport {
  @Autowired ImprovementService improvements;
  @Autowired FeedbackService feedback;
  @Autowired QueryRecordService records;
  @Autowired AnswerHistoryService history;
  @Autowired ContentPurgeService purge;

  String kb() {
    return str(
        db.one(
            "SELECT d.kb_id FROM documents d JOIN document_versions v ON v.document_id=d.id WHERE v.id=?",
            version),
        "kb_id");
  }

  String record(Actor who, String status, List<Map<String, Object>> sources) {
    String request = id();
    var query =
        new RetrievalService.Query("合成改进问题", null, List.of(kb()), "keyword", 6, false, null);
    var result = new LinkedHashMap<String, Object>();
    result.put("evidence", sources);
    result.put("evidence_status", status);
    result.put("configuration_id", "");
    records.save(
        who, query, new RetrievalService.Scope(List.of(version), false, "", -1), result, request);
    return request;
  }

  Actor reader() {
    String id = id();
    db.exec(
        "INSERT INTO members(id,tenant_id,name,role) VALUES(?,?,'Synthetic reader','USER')",
        id,
        tenant);
    db.exec(
        "INSERT INTO permissions(tenant_id,resource_id,subject_id,action) VALUES(?,?,?,'read')",
        tenant,
        kb(),
        id);
    return new Actor(tenant, id, "MEMBER", "USER");
  }

  @Test
  void noResultTasksDeduplicateAndRequireAuthorizedEditorsForResolution() {
    var reader = reader();
    String record = record(reader, "NO_MATCH", List.of());
    var input =
        new ImprovementService.Create(
            "NO_RESULT", UUID.fromString(record), UUID.fromString(kb()), "合成补充说明");
    var task = improvements.create(reader, input);
    String taskId = str(task, "id");
    assertThat(str(improvements.create(reader, input), "id")).isEqualTo(taskId);
    assertThat(task.get("can_manage")).isEqualTo(false);
    assertThatThrownBy(
            () ->
                improvements.update(
                    reader, taskId, new ImprovementService.Update(0, "RESOLVED", null, "说明")))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                improvements.update(
                    actor, taskId, new ImprovementService.Update(0, "RESOLVED", null, "")))
        .isInstanceOf(IllegalArgumentException.class);
    var resolved =
        improvements.update(
            actor,
            taskId,
            new ImprovementService.Update(
                0, "RESOLVED", UUID.fromString(actor.subject()), "已补充资料，待发布验收"));
    assertThat(str(resolved, "state")).isEqualTo("RESOLVED");
    assertThatThrownBy(
            () ->
                improvements.update(
                    actor, taskId, new ImprovementService.Update(0, "OPEN", null, "")))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () -> improvements.get(new Actor(id(), reader.subject(), "MEMBER", "USER"), taskId))
        .isInstanceOf(ApiException.class);
    db.exec("DELETE FROM permissions WHERE tenant_id=? AND subject_id=?", tenant, reader.subject());
    assertThat(improvements.list(reader)).isEmpty();
    assertThat(improvements.get(actor, taskId)).containsEntry("can_manage", true);
  }

  @Test
  void feedbackRevisionDependenciesAndPhysicalPurgeCoverDerivedTasks() {
    db.exec("UPDATE document_versions SET ever_published=TRUE WHERE id=?", version);
    String chunk = chunk(0, "改进合成证据", 8, true, "page:1");
    String document =
        str(db.one("SELECT document_id FROM document_versions WHERE id=?", version), "document_id");
    var source =
        Map.<String, Object>of(
            "id", chunk, "document_id", document, "version_id", version, "content", "改进合成证据");
    var reader = reader();
    String record = record(reader, "AVAILABLE", List.of(source));
    String answer =
        history.save(
            reader,
            new RetrievalService.Query("合成改进问题", null, List.of(kb()), null, 6, false, null),
            "合成答案",
            List.of(source),
            null,
            record);
    feedback.save(
        reader, answer, new FeedbackService.Feedback("incorrect", "WRONG_SOURCE", "需要复核", 0L));
    assertThatThrownBy(
            () ->
                feedback.save(
                    reader, answer, new FeedbackService.Feedback("helpful", null, "", 0L)))
        .isInstanceOf(ApiException.class);
    var task =
        improvements.create(
            reader,
            new ImprovementService.Create(
                "ANSWER", UUID.fromString(answer), UUID.fromString(kb()), "请检查来源"));
    assertThat(str(task, "reason")).isEqualTo("WRONG_SOURCE");
    String editor = id();
    db.exec(
        "INSERT INTO members(id,tenant_id,name,role) VALUES(?,?,'Edit-only manager','KNOWLEDGE_MANAGER')",
        editor,
        tenant);
    db.exec(
        "INSERT INTO permissions(tenant_id,resource_id,subject_id,action) VALUES(?,?,?,'edit')",
        tenant,
        kb(),
        editor);
    assertThat(improvements.assignees(actor, str(task, "id")))
        .noneMatch(row -> editor.equals(str(row, "id")));
    assertThatThrownBy(
            () ->
                improvements.update(
                    actor,
                    str(task, "id"),
                    new ImprovementService.Update(0, "IN_PROGRESS", UUID.fromString(editor), "")))
        .isInstanceOf(ApiException.class);

    assertThat(improvements.get(actor, str(task, "id")).get("query").toString()).contains(record);
    db.exec("UPDATE chunks SET enabled=FALSE WHERE id=?", chunk);
    assertThatThrownBy(
            () ->
                feedback.save(
                    reader, answer, new FeedbackService.Feedback("helpful", null, "", 1L)))
        .isInstanceOf(ApiException.class);
    assertThat(improvements.list(actor)).isEmpty();
    purge.document(tenant, document);
    assertThat(db.list("SELECT id FROM improvement_tasks WHERE tenant_id=?", tenant)).isEmpty();
    assertThat(db.list("SELECT id FROM query_records WHERE tenant_id=?", tenant)).isEmpty();
  }

  @Test
  void scoredOrUnavailableQueriesCannotMasqueradeAsNoResult() {
    for (String status : List.of("AVAILABLE", "SCORE_UNAVAILABLE", "DEGRADED")) {
      String record = record(actor, status, List.of());
      assertThatThrownBy(
              () ->
                  improvements.create(
                      actor,
                      new ImprovementService.Create(
                          "NO_RESULT", UUID.fromString(record), UUID.fromString(kb()), "")))
          .isInstanceOfSatisfying(
              ApiException.class, e -> assertThat(e.code).isEqualTo("NO_RESULT_REQUIRED"));
    }
  }
}
