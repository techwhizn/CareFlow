package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import com.careflow.platform.RetrievalService.Query;
import com.careflow.platform.RetrievalService.Scope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AnswerHistoryService {
  private final QueryReservationService reservations;
  private final Db db;
  private final Identity auth;
  private final EvidenceAuthorization evidence;
  private final ConversationRepository conversations;
  private final ObjectMapper json;
  private final TransactionTemplate tx;

  public AnswerHistoryService(
      QueryReservationService reservations,
      Db db,
      Identity auth,
      EvidenceAuthorization evidence,
      ConversationRepository conversations,
      ObjectMapper json,
      TransactionTemplate tx) {
    this.reservations = reservations;
    this.db = db;
    this.auth = auth;
    this.evidence = evidence;
    this.conversations = conversations;
    this.json = json;
    this.tx = tx;
  }

  public List<Map<String, Object>> dependencies(String answer) {
    return db.list(
        "SELECT document_id,version_id,chunk_id AS id FROM answer_evidence WHERE answer_id=?",
        answer);
  }

  public void validate(Actor actor, Map<String, Object> answer) {
    if (!actor.tenant().equals(str(answer, "tenant_id"))
        || !actor.subject().equals(str(answer, "subject_id"))) throw ApiException.hidden();
    if (!str(answer, "conversation_id").isBlank())
      conversations.owned(actor, str(answer, "conversation_id"));
    for (var row : dependencies(str(answer, "id")))
      evidence.check(actor, row, new Scope(List.of(), false, str(answer, "application_id"), -1));
  }

  public boolean visible(Actor actor, Map<String, Object> answer) {
    try {
      validate(actor, answer);
      return true;
    } catch (ApiException e) {
      if (e.status != 404 && e.status != 401) throw e;
      return false;
    }
  }

  public List<Map<String, Object>> history(Actor actor, String conversation) {
    auth.authorizeRequest(actor, "GET", "/api/v1/answers");
    if (conversation != null) conversations.owned(actor, conversation);
    var rows =
        conversation == null
            ? db.list(
                "SELECT * FROM answers WHERE tenant_id=? AND subject_id=? ORDER BY created_at DESC,id DESC LIMIT 100",
                actor.tenant(),
                actor.subject())
            : db.list(
                "SELECT * FROM answers WHERE tenant_id=? AND subject_id=? AND conversation_id=? ORDER BY turn_no DESC LIMIT 100",
                actor.tenant(),
                actor.subject(),
                conversation);
    return rows.stream().filter(row -> visible(actor, row)).toList();
  }

  public List<Map<String, Object>> recent(Actor actor, String conversation) {
    conversations.owned(actor, conversation);
    return db
        .list(
            "SELECT * FROM answers WHERE tenant_id=? AND subject_id=? AND conversation_id=? ORDER BY turn_no DESC LIMIT 6",
            actor.tenant(),
            actor.subject(),
            conversation)
        .stream()
        .filter(row -> visible(actor, row))
        .toList();
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> answer(Actor actor, String id) {
    auth.authorizeRequest(actor, "GET", "/api/v1/answers");
    var answer =
        db.one(
            "SELECT * FROM answers WHERE tenant_id=? AND subject_id=? AND id=?",
            actor.tenant(),
            actor.subject(),
            UUID.fromString(id).toString());
    validate(actor, answer);
    var output = new LinkedHashMap<>(answer);
    List<Map<String, Object>> citations = new ArrayList<>();
    for (var row :
        db.list(
            "SELECT * FROM answer_evidence WHERE answer_id=? AND evidence_role='CURRENT'", id)) {
      try {
        if (row.get("evidence_json") != null)
          citations.add(json.readValue(str(row, "evidence_json"), Map.class));
        else
          citations.add(
              db.one(
                  "SELECT c.id,c.version_id,c.content,c.location,c.revision,d.id AS document_id,d.title FROM chunks c JOIN document_versions v ON v.id=c.version_id JOIN documents d ON d.id=v.document_id WHERE c.tenant_id=? AND c.id=?",
                  actor.tenant(),
                  str(row, "chunk_id")));
      } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
        throw new IllegalStateException("Invalid stored citation", error);
      }
    }
    output.put("evidence", citations);
    return output;
  }

  public String save(
      Actor actor,
      Query query,
      String content,
      List<Map<String, Object>> sources,
      ConversationService.Context context,
      String request) {
    return tx.execute(
        status -> {
          auth.lock(actor);
          reservations.result(actor, request, true);
          String application =
              actor.app() ? actor.subject() : Objects.toString(query.application_id(), "");
          var scope = new Scope(List.of(), false, application, -1);
          var all = new LinkedHashMap<String, Map<String, Object>>();
          for (var source : sources) {
            evidence.check(actor, source, scope);
            var snapshot = new LinkedHashMap<>(source);
            var ids =
                source.get("covered_chunk_ids") instanceof List<?> covered
                    ? covered
                    : List.of(str(source, "id"));
            var originals = new ArrayList<Map<String, Object>>();
            for (Object chunk : ids)
              originals.add(
                  db.one(
                      "SELECT id,version_id,revision,source_text,content,location,origin FROM chunks WHERE tenant_id=? AND version_id=? AND id=? AND enabled=TRUE",
                      actor.tenant(),
                      str(source, "version_id"),
                      chunk));
            snapshot.put("source_chunks", originals);
            all.put(str(source, "id"), snapshot);
          }
          List<Map<String, Object>> dependencies = new ArrayList<>();
          if (context != null) dependencies.addAll(context.dependencies());
          for (var source : sources)
            if (source.get("covered_chunk_ids") instanceof List<?> covered) {
              for (Object id : covered)
                dependencies.add(
                    Map.of(
                        "id",
                        id.toString(),
                        "document_id",
                        str(source, "document_id"),
                        "version_id",
                        str(source, "version_id")));
            }
          for (var source : dependencies) evidence.check(actor, source, scope);
          Long turn = context == null ? null : conversations.advance(actor, context.id(), request);
          String answer = id();
          db.exec(
              "INSERT INTO answers(id,tenant_id,subject_id,application_id,question,content,conversation_id,turn_no) VALUES(?,?,?,?,?,?,?,?)",
              answer,
              actor.tenant(),
              actor.subject(),
              application,
              query.query(),
              content,
              context == null ? null : context.id(),
              turn);
          if (request != null)
            db.exec(
                "UPDATE answers SET query_record_id=? WHERE tenant_id=? AND id=?",
                request,
                actor.tenant(),
                answer);
          for (var source : all.values()) {
            try {
              db.exec(
                  "INSERT INTO answer_evidence(answer_id,document_id,version_id,chunk_id,evidence_role,evidence_json) VALUES(?,?,?,?,'CURRENT',?)",
                  answer,
                  str(source, "document_id"),
                  str(source, "version_id"),
                  str(source, "id"),
                  json.writeValueAsString(source));
            } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
              throw new IllegalArgumentException("Invalid evidence snapshot", error);
            }
          }
          for (var source : dependencies)
            if (all.putIfAbsent(str(source, "id"), source) == null)
              db.exec(
                  "INSERT INTO answer_evidence(answer_id,document_id,version_id,chunk_id,evidence_role) VALUES(?,?,?,?,'CONTEXT')",
                  answer,
                  str(source, "document_id"),
                  str(source, "version_id"),
                  str(source, "id"));
          return answer;
        });
  }
}
