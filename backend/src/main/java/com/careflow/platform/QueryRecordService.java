package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import com.careflow.platform.RetrievalService.Query;
import com.careflow.platform.RetrievalService.Scope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QueryRecordService {
  private final Db db;
  private final Identity auth;
  private final EvidenceAuthorization evidence;
  private final ObjectMapper json;
  private final ConversationRepository conversations;

  public QueryRecordService(
      Db db,
      Identity auth,
      EvidenceAuthorization evidence,
      ObjectMapper json,
      ConversationRepository conversations) {
    this.db = db;
    this.auth = auth;
    this.evidence = evidence;
    this.json = json;
    this.conversations = conversations;
  }

  @Transactional
  @SuppressWarnings("unchecked")
  public void save(Actor actor, Query query, Scope scope, Map<String, Object> result, String id) {
    auth.lock(actor);
    var sources = (List<Map<String, Object>>) result.get("evidence");
    for (var source : sources) evidence.check(actor, source, scope);
    var options = new LinkedHashMap<String, Object>();
    options.put("mode", query.mode());
    options.put("limit", query.limit());
    options.put("minimum_rerank_score", result.get("minimum_rerank_score"));
    options.put("filters", query.filters());
    options.put("publication_versions", scope.versions());
    options.put(
        "application_configuration_id",
        scope.applicationPolicy() == null ? "" : scope.applicationPolicy().id());
    try {
      db.exec(
          "INSERT INTO query_records(id,tenant_id,subject_id,subject_kind,application_id,question,configuration_id,application_revision,knowledge_base_ids,query_options,evidence_status) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
          id,
          actor.tenant(),
          actor.subject(),
          actor.kind(),
          scope.application(),
          query.query(),
          result.get("configuration_id"),
          scope.applicationRevision(),
          conversations.binding(query.knowledge_base_ids()),
          json.writeValueAsString(options),
          result.get("evidence_status"));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid query options", e);
    }
    var dependencies = new LinkedHashMap<String, Map<String, Object>>();
    for (var source : sources) {
      dependencies.put(str(source, "id"), source);
      if (source.get("covered_chunk_ids") instanceof List<?> ids)
        for (Object chunk : ids)
          dependencies.put(
              chunk.toString(),
              Map.of(
                  "id",
                  chunk.toString(),
                  "document_id",
                  str(source, "document_id"),
                  "version_id",
                  str(source, "version_id")));
    }
    for (var source : dependencies.values())
      db.exec(
          "INSERT INTO query_record_evidence(query_record_id,document_id,version_id,chunk_id) VALUES(?,?,?,?)",
          id,
          str(source, "document_id"),
          str(source, "version_id"),
          str(source, "id"));
    result.put("query_record_id", id);
  }

  public Map<String, Object> owned(Actor actor, String id) {
    var row =
        db.one(
            "SELECT * FROM query_records WHERE tenant_id=? AND subject_id=? AND subject_kind=? AND id=?",
            actor.tenant(),
            actor.subject(),
            actor.kind(),
            UUID.fromString(id).toString());
    validate(actor, row);
    return row;
  }

  public void validate(Actor actor, Map<String, Object> row) {
    if (!actor.tenant().equals(str(row, "tenant_id"))) throw ApiException.hidden();
    String app = str(row, "application_id");
    if (!app.isBlank())
      db.one(
          "SELECT id FROM applications WHERE tenant_id=? AND id=? AND published=TRUE",
          actor.tenant(),
          app);
    for (var source :
        db.list(
            "SELECT document_id,version_id,chunk_id AS id FROM query_record_evidence WHERE query_record_id=?",
            str(row, "id"))) evidence.check(actor, source, new Scope(List.of(), false, app, -1));
  }
}
