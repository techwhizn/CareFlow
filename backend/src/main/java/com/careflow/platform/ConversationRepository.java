package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ConversationRepository {
  private final Db db;
  private final Identity auth;
  private final ObjectMapper json;

  public ConversationRepository(Db db, Identity auth, ObjectMapper json) {
    this.db = db;
    this.auth = auth;
    this.json = json;
  }

  public String binding(List<String> ids) {
    if (ids != null && ids.size() > 20)
      throw new IllegalArgumentException("Too many knowledge bases");
    try {
      return json.writeValueAsString(
          ids == null
              ? List.of()
              : ids.stream()
                  .map(id -> UUID.fromString(id).toString())
                  .distinct()
                  .sorted()
                  .toList());
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException();
    }
  }

  @Transactional
  public String create(Actor actor, String application, List<String> bases) {
    auth.lock(actor);
    application = application == null ? "" : application;
    if (actor.app()) {
      if (!application.isBlank() && !application.equals(actor.subject()))
        throw ApiException.hidden();
      application = actor.subject();
    }
    if (!application.isBlank())
      db.one(
          "SELECT id FROM applications WHERE tenant_id=? AND id=? AND published=TRUE",
          actor.tenant(),
          application);
    if (bases != null)
      for (String kb : bases) {
        auth.kb(actor, kb, "read");
        if (!application.isBlank())
          db.one(
              "SELECT kb_id FROM application_bindings WHERE tenant_id=? AND application_id=? AND kb_id=?",
              actor.tenant(),
              application,
              kb);
      }
    String id = id();
    db.exec(
        "INSERT INTO conversations(id,tenant_id,subject_id,subject_kind,application_id,knowledge_base_ids) VALUES(?,?,?,?,?,?)",
        id,
        actor.tenant(),
        actor.subject(),
        actor.kind(),
        application,
        binding(bases));
    return id;
  }

  public Map<String, Object> owned(Actor actor, String conversation) {
    var row =
        db.one(
            "SELECT * FROM conversations WHERE tenant_id=? AND subject_id=? AND subject_kind=? AND id=?",
            actor.tenant(),
            actor.subject(),
            actor.kind(),
            UUID.fromString(conversation).toString());
    if (!str(row, "application_id").isBlank())
      db.one(
          "SELECT id FROM applications WHERE tenant_id=? AND id=? AND published=TRUE",
          actor.tenant(),
          str(row, "application_id"));
    return row;
  }

  @Transactional
  public Map<String, Object> claim(
      Actor actor, String conversation, String application, List<String> bases, String request) {
    auth.lock(actor);
    var row = owned(actor, conversation);
    if (!str(row, "application_id").equals(Objects.toString(application, ""))
        || !str(row, "knowledge_base_ids").equals(binding(bases)))
      throw new ApiException(409, "CONVERSATION_SCOPE_MISMATCH", "切换应用或知识范围时请新建会话");
    if (db.exec(
            "UPDATE conversations SET active_request_id=?,busy_until=? WHERE tenant_id=? AND id=? AND (active_request_id IS NULL OR busy_until<CURRENT_TIMESTAMP)",
            request,
            java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(150)),
            actor.tenant(),
            conversation)
        != 1) throw new ApiException(409, "CONVERSATION_BUSY", "该会话已有回答正在生成");
    return row;
  }

  public void fence(Actor actor, String conversation, String request) {
    owned(actor, conversation);
    db.one(
        "SELECT id FROM conversations WHERE tenant_id=? AND id=? AND active_request_id=? AND busy_until>CURRENT_TIMESTAMP",
        actor.tenant(),
        conversation,
        request);
  }

  public long advance(Actor actor, String conversation, String request) {
    fence(actor, conversation, request);
    if (db.exec(
            "UPDATE conversations SET revision=revision+1,active_request_id=NULL,busy_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=? AND active_request_id=? AND busy_until>CURRENT_TIMESTAMP",
            actor.tenant(),
            conversation,
            request)
        != 1) throw ApiException.conflict();
    return num(owned(actor, conversation), "revision");
  }

  public void release(Actor actor, String request) {
    db.exec(
        "UPDATE conversations SET active_request_id=NULL,busy_until=NULL WHERE tenant_id=? AND subject_id=? AND subject_kind=? AND active_request_id=?",
        actor.tenant(),
        actor.subject(),
        actor.kind(),
        request);
  }
}
