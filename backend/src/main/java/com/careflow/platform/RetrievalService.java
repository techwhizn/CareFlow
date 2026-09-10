package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RetrievalService {
  private final Db db;
  private final Identity auth;
  private final WorkerClient worker;
  private final TransactionTemplate tx;

  public RetrievalService(Db db, Identity auth, WorkerClient worker, TransactionTemplate tx) {
    this.db = db;
    this.auth = auth;
    this.worker = worker;
    this.tx = tx;
  }

  public record Query(
      String query,
      String application_id,
      List<String> knowledge_base_ids,
      String mode,
      int limit,
      boolean debug,
      Double minimum_rerank_score) {}

  public record Scope(
      List<String> versions, boolean degraded, String application, long applicationRevision) {}

  public Scope scope(Actor actor, Query q) {
    if (q.query() == null
        || q.query().isBlank()
        || q.query().length() > 4000
        || q.limit() < 1
        || q.limit() > 20
        || !Set.of("hybrid", "semantic", "keyword").contains(q.mode()))
      throw new IllegalArgumentException();
    if (q.minimum_rerank_score() != null && !Double.isFinite(q.minimum_rerank_score()))
      throw new IllegalArgumentException("minimum_rerank_score must be finite");
    String app = q.application_id();
    if (actor.app()) {
      if (app != null && !app.equals(actor.subject())) throw ApiException.hidden();
      app = actor.subject();
    }
    Set<String> allowed = null;
    boolean degrade = false;
    long appRevision = -1;
    if (app != null && !app.isBlank()) {
      var a =
          db.one(
              "SELECT * FROM applications WHERE tenant_id=? AND id=? AND published=TRUE",
              actor.tenant(),
              app);
      degrade = bool(a, "allow_degraded");
      appRevision = num(a, "revision");
      allowed = new HashSet<>();
      for (var b :
          db.list(
              "SELECT kb_id FROM application_bindings WHERE tenant_id=? AND application_id=?",
              actor.tenant(),
              app)) allowed.add(str(b, "kb_id"));
    }
    List<String> versions = new ArrayList<>();
    for (var d :
        db.list(
            "SELECT d.*,v.id AS version_id FROM documents d JOIN document_versions v ON v.id=d.published_version JOIN knowledge_bases k ON k.id=d.kb_id WHERE d.tenant_id=? AND d.status='ACTIVE' AND k.status='ACTIVE' AND v.state='READY' AND (v.valid_from IS NULL OR v.valid_from<=CURRENT_TIMESTAMP) AND (v.valid_until IS NULL OR v.valid_until>CURRENT_TIMESTAMP)",
            actor.tenant())) {
      String kb = str(d, "kb_id");
      if (allowed != null && !allowed.contains(kb)) continue;
      if (q.knowledge_base_ids() != null
          && !q.knowledge_base_ids().isEmpty()
          && !q.knowledge_base_ids().contains(kb)) continue;
      try {
        auth.document(actor, str(d, "id"), "read");
        versions.add(str(d, "version_id"));
      } catch (ApiException e) {
        if (e.status != 404) throw e;
      }
    }
    return new Scope(versions, degrade, app == null ? "" : app, appRevision);
  }

  public String reserve(Actor actor, String key, String app) {
    return tx.execute(
        status -> {
          auth.lock(actor);
          if (key == null || key.isBlank() || key.length() > 100)
            throw new IllegalArgumentException();
          var previous =
              db.list(
                  "SELECT id FROM usage_events WHERE tenant_id=? AND subject_id=? AND request_key=? AND resource_type='QUERY'",
                  actor.tenant(),
                  actor.subject(),
                  key);
          if (!previous.isEmpty())
            throw new ApiException(409, "DUPLICATE_REQUEST", "此请求已处理或处理中，请查询历史结果，勿重复计费");
          if (db.exec(
                  "UPDATE tenants SET queries_reserved=queries_reserved+1 WHERE id=? AND queries_used+queries_reserved<query_limit",
                  actor.tenant())
              != 1) throw new ApiException(429, "QUOTA_EXCEEDED", "查询额度不足");
          String event = id();
          db.exec(
              "INSERT INTO usage_events(id,tenant_id,application_id,subject_id,request_key,resource_type,amount,state) VALUES(?,?,?,?,?,'QUERY',1,'RESERVED')",
              event,
              actor.tenant(),
              app,
              actor.subject(),
              key);
          return event;
        });
  }

  public void settle(Actor actor, String event, boolean success) {
    tx.executeWithoutResult(
        status -> {
          auth.lock(actor);
          if (db.exec(
                  "UPDATE usage_events SET state=? WHERE id=? AND tenant_id=? AND state='RESERVED'",
                  success ? "SETTLED" : "RELEASED",
                  event,
                  actor.tenant())
              == 1)
            db.exec(
                "UPDATE tenants SET queries_reserved=queries_reserved-1,queries_used=queries_used+? WHERE id=?",
                success ? 1 : 0,
                actor.tenant());
        });
  }

  // Resolve every model candidate back to authoritative content. Never forward worker-provided text
  // blindly.
  @SuppressWarnings("unchecked")
  public Map<String, Object> search(Actor actor, Query q, Scope scope, String authorization) {
    Map<String, Object> recall =
        scope.versions().isEmpty()
            ? Map.of("dense", List.of(), "bm25", List.of(), "fused", List.of(), "degraded", false)
            : worker.call(
                "/internal/v1/recall",
                Map.of(
                    "tenant_id",
                    actor.tenant(),
                    "version_ids",
                    scope.versions(),
                    "query",
                    q.query(),
                    "mode",
                    q.mode(),
                    "allow_degraded",
                    scope.degraded()));
    List<Map<String, Object>> evidence = new ArrayList<>();
    var fused = (List<Map<String, Object>>) recall.getOrDefault("fused", List.of());
    reauthenticate(actor, authorization);
    for (var hit : fused) {
      String chunk = str(hit, "id");
      var rows =
          db.list(
              "SELECT c.*,v.document_id,d.title FROM chunks c JOIN document_versions v ON v.id=c.version_id JOIN documents d ON d.id=v.document_id WHERE c.tenant_id=? AND c.id=? AND c.enabled=TRUE",
              actor.tenant(),
              chunk);
      if (rows.isEmpty()) continue;
      var c = rows.getFirst();
      if (!scope.versions().contains(str(c, "version_id"))) continue;
      try {
        checkEvidence(actor, c, scope);
        evidence.add(c);
      } catch (ApiException e) {
        if (e.status != 404) throw e;
      }
    }
    // Recheck before disclosing any candidate text to an external model.
    reauthenticate(actor, authorization);
    for (var c : evidence) checkEvidence(actor, c, scope);
    Map<String, Object> ranked =
        evidence.isEmpty()
            ? Map.of("results", List.of(), "degraded", false)
            : worker.call(
                "/internal/v1/rerank",
                Map.of(
                    "query",
                    q.query(),
                    "candidates",
                    evidence.stream()
                        .map(c -> Map.of("id", str(c, "id"), "content", str(c, "content")))
                        .toList(),
                    "allow_degraded",
                    scope.degraded()));
    var byId = new HashMap<String, Map<String, Object>>();
    for (var e : evidence) byId.put(str(e, "id"), e);
    boolean debug = q.debug() && !actor.app() && !actor.role().equals("USER");
    var selection =
        EvidenceSelection.select(
            byId,
            (List<Map<String, Object>>) ranked.getOrDefault("results", List.of()),
            q.limit(),
            q.minimum_rerank_score(),
            Boolean.TRUE.equals(ranked.get("degraded")),
            debug);
    reauthenticate(actor, authorization);
    for (var c : evidence) checkEvidence(actor, c, scope);
    var response = new LinkedHashMap<String, Object>();
    response.put("evidence", selection.evidence());
    response.put("minimum_rerank_score", q.minimum_rerank_score());
    response.put("trace_id", id());
    response.put("publication_versions", scope.versions());
    response.put("application_revision", scope.applicationRevision());
    response.put(
        "degraded",
        Boolean.TRUE.equals(recall.get("degraded")) || Boolean.TRUE.equals(ranked.get("degraded")));
    if (q.debug() && !actor.app() && !actor.role().equals("USER")) {
      var authorizedIds = byId.keySet();
      var safeRecall = new LinkedHashMap<String, Object>();
      for (String lane : List.of("dense", "bm25", "fused")) {
        var hits = (List<Map<String, Object>>) recall.getOrDefault(lane, List.of());
        safeRecall.put(
            lane, hits.stream().filter(hit -> authorizedIds.contains(str(hit, "id"))).toList());
      }
      response.put("recall", safeRecall);
      response.put(
          "rerank",
          Map.of(
              "results",
                  ((List<Map<String, Object>>) ranked.getOrDefault("results", List.of()))
                      .stream().filter(hit -> authorizedIds.contains(str(hit, "id"))).toList(),
              "degraded", Boolean.TRUE.equals(ranked.get("degraded"))));
      response.put("excluded", selection.excluded());
    }
    return response;
  }

  public void reauthenticate(Actor expected, String authorization) {
    Actor now = auth.authenticate(authorization);
    if (!expected.equals(now)) throw ApiException.hidden();
  }

  public void checkEvidence(Actor actor, Map<String, Object> c, Scope scope) {
    var d = auth.document(actor, str(c, "document_id"), "read");
    var k = auth.kb(actor, str(d, "kb_id"), "read");
    if (!str(k, "status").equals("ACTIVE") || !str(d, "status").equals("ACTIVE"))
      throw ApiException.hidden();
    var v =
        db.one(
            "SELECT id FROM document_versions WHERE tenant_id=? AND id=? AND ever_published=TRUE AND (valid_from IS NULL OR valid_from<=CURRENT_TIMESTAMP) AND (valid_until IS NULL OR valid_until>CURRENT_TIMESTAMP)",
            actor.tenant(),
            str(c, "version_id"));
    if (!scope.application().isBlank()) {
      db.one(
          "SELECT b.kb_id FROM application_bindings b JOIN applications a ON a.id=b.application_id WHERE b.tenant_id=? AND b.application_id=? AND b.kb_id=? AND a.published=TRUE",
          actor.tenant(),
          scope.application(),
          str(d, "kb_id"));
    }
  }

  public Map<String, Object> generate(String question, List<Map<String, Object>> evidence) {
    return worker.call(
        "/internal/v1/generate",
        Map.of(
            "query",
            question,
            "evidence",
            evidence.stream()
                .map(c -> Map.of("id", str(c, "id"), "content", str(c, "content")))
                .toList()));
  }

  public String saveAnswer(
      Actor actor, Query q, String content, List<Map<String, Object>> evidence) {
    return tx.execute(
        status -> {
          auth.lock(actor);
          String answer = id();
          for (var c : evidence)
            checkEvidence(
                actor,
                c,
                new Scope(List.of(), false, Objects.toString(q.application_id(), ""), -1));
          db.exec(
              "INSERT INTO answers(id,tenant_id,subject_id,application_id,question,content) VALUES(?,?,?,?,?,?)",
              answer,
              actor.tenant(),
              actor.subject(),
              q.application_id(),
              q.query(),
              content);
          for (var c : evidence)
            db.exec(
                "INSERT INTO answer_evidence(answer_id,document_id,version_id,chunk_id) VALUES(?,?,?,?)",
                answer,
                str(c, "document_id"),
                str(c, "version_id"),
                str(c, "id"));
          return answer;
        });
  }
}
