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
  private final EntitlementService entitlements;
  private final MetadataFilters metadataFilters;
  private final ConfiguredModelRouting modelRouting;
  private final EvidenceContextService contexts;
  private final EvidenceAuthorization evidenceAuthorization;
  private final AnswerHistoryService history;

  public RetrievalService(
      Db db,
      Identity auth,
      WorkerClient worker,
      TransactionTemplate tx,
      EntitlementService entitlements,
      MetadataFilters metadataFilters,
      ConfiguredModelRouting modelRouting,
      EvidenceContextService contexts,
      EvidenceAuthorization evidenceAuthorization,
      AnswerHistoryService history) {
    this.db = db;
    this.auth = auth;
    this.worker = worker;
    this.tx = tx;
    this.entitlements = entitlements;
    this.metadataFilters = metadataFilters;
    this.modelRouting = modelRouting;
    this.contexts = contexts;
    this.evidenceAuthorization = evidenceAuthorization;
    this.history = history;
  }

  public record Query(
      String query,
      String application_id,
      List<String> knowledge_base_ids,
      String mode,
      int limit,
      boolean debug,
      Double minimum_rerank_score,
      List<MetadataFilters.Rule> filters,
      String conversation_id) {
    public Query(
        String query,
        String application_id,
        List<String> knowledge_base_ids,
        String mode,
        int limit,
        boolean debug,
        Double minimum_rerank_score,
        List<MetadataFilters.Rule> filters) {
      this(
          query,
          application_id,
          knowledge_base_ids,
          mode,
          limit,
          debug,
          minimum_rerank_score,
          filters,
          null);
    }

    public Query(
        String query,
        String application_id,
        List<String> knowledge_base_ids,
        String mode,
        int limit,
        boolean debug,
        Double minimum_rerank_score) {
      this(
          query,
          application_id,
          knowledge_base_ids,
          mode,
          limit,
          debug,
          minimum_rerank_score,
          List.of());
    }
  }

  public record Scope(
      List<String> versions,
      boolean degraded,
      String application,
      long applicationRevision,
      ConfiguredModelRouting.QueryConfiguration configuration) {
    public Scope(
        List<String> versions, boolean degraded, String application, long applicationRevision) {
      this(versions, degraded, application, applicationRevision, null);
    }
  }

  public Scope scope(Actor actor, Query q) {
    if (q.query() == null
        || q.query().isBlank()
        || q.query().length() > 4000
        || q.limit() < 1
        || q.limit() > 20
        || (q.mode() != null && !Set.of("hybrid", "semantic", "keyword").contains(q.mode())))
      throw new IllegalArgumentException();
    if (q.minimum_rerank_score() != null && !Double.isFinite(q.minimum_rerank_score()))
      throw new IllegalArgumentException("minimum_rerank_score must be finite");
    QueryProcessing.process(q.query());
    var metadata = metadataFilters.compile(q.filters());
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
    var now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
    List<String> versions = new ArrayList<>();
    for (var d :
        db.list(
            "SELECT d.*,v.id AS version_id FROM documents d JOIN document_versions v ON v.id=d.published_version JOIN knowledge_bases k ON k.id=d.kb_id WHERE d.tenant_id=? AND d.status='ACTIVE' AND (d.valid_from IS NULL OR d.valid_from<=?) AND (d.valid_until IS NULL OR d.valid_until>?) AND k.status='ACTIVE' AND v.state='READY' AND (v.valid_from IS NULL OR v.valid_from<=CURRENT_TIMESTAMP) AND (v.valid_until IS NULL OR v.valid_until>CURRENT_TIMESTAMP)",
            actor.tenant(),
            now,
            now)) {
      String kb = str(d, "kb_id");
      if (allowed != null && !allowed.contains(kb)) continue;
      if (q.knowledge_base_ids() != null
          && !q.knowledge_base_ids().isEmpty()
          && !q.knowledge_base_ids().contains(kb)) continue;
      try {
        auth.document(actor, str(d, "id"), "read");
        if (metadata.test(d)) {
          versions.add(str(d, "version_id"));
        }
      } catch (ApiException e) {
        if (e.status != 404) throw e;
      }
    }
    return new Scope(
        versions,
        degrade,
        app == null ? "" : app,
        appRevision,
        modelRouting.queryConfiguration(actor.tenant(), versions));
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
          entitlements.query(actor.tenant());
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
    return search(actor, q, scope, authorization, QueryProcessing.process(q.query()));
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> search(
      Actor actor,
      Query q,
      Scope scope,
      String authorization,
      QueryProcessing.Processed processed) {
    long started = System.nanoTime();
    var queryConfiguration = scope.configuration();
    boolean allowDegraded =
        queryConfiguration == null
            ? scope.degraded()
            : scope.degraded() && queryConfiguration.retrieval().allow_degraded();
    if (scope.application().isBlank() && queryConfiguration != null)
      allowDegraded = queryConfiguration.retrieval().allow_degraded();
    int resultLimit =
        queryConfiguration == null
            ? q.limit()
            : Math.min(q.limit(), queryConfiguration.retrieval().limit());
    Double minimumScore = q.minimum_rerank_score();
    if (queryConfiguration != null && queryConfiguration.retrieval().minimum_rerank_score() != null)
      minimumScore =
          minimumScore == null
              ? queryConfiguration.retrieval().minimum_rerank_score()
              : Math.max(minimumScore, queryConfiguration.retrieval().minimum_rerank_score());
    long recallStarted = System.nanoTime();
    Map<String, Object> recall =
        modelRouting.recall(
            actor.tenant(),
            scope.versions(),
            processed.rewritten(),
            q.mode() == null
                ? (queryConfiguration == null ? "hybrid" : queryConfiguration.retrieval().mode())
                : q.mode(),
            allowDegraded,
            () -> {
              reauthenticate(actor, authorization);
              for (String version : scope.versions()) {
                var row = auth.version(actor, version, "read");
                checkEvidence(
                    actor,
                    Map.of("document_id", str(row, "document_id"), "version_id", version),
                    scope);
              }
            });
    long recallFinished = System.nanoTime();
    List<Map<String, Object>> evidence = new ArrayList<>();
    var fused = (List<Map<String, Object>>) recall.getOrDefault("fused", List.of());
    reauthenticate(actor, authorization);
    for (var hit : fused) {
      String chunk = str(hit, "id");
      var rows =
          db.list(
              "SELECT c.*,v.document_id,d.title,d.valid_from AS document_valid_from,d.valid_until AS document_valid_until,v.valid_from AS version_valid_from,v.valid_until AS version_valid_until FROM chunks c JOIN document_versions v ON v.id=c.version_id JOIN documents d ON d.id=v.document_id WHERE c.tenant_id=? AND c.id=? AND c.enabled=TRUE",
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
    Map<String, Object> rerankRequest =
        new LinkedHashMap<>(
            Map.of(
                "query",
                processed.rewritten(),
                "candidates",
                evidence.stream()
                    .map(
                        c ->
                            Map.of(
                                "id",
                                str(c, "id"),
                                "content",
                                str(c, "content"),
                                "title",
                                str(c, "title"),
                                "document_id",
                                str(c, "document_id"),
                                "version_id",
                                str(c, "version_id"),
                                "applicability",
                                Map.of(
                                    "document_from",
                                    str(c, "document_valid_from"),
                                    "document_until",
                                    str(c, "document_valid_until"),
                                    "version_from",
                                    str(c, "version_valid_from"),
                                    "version_until",
                                    str(c, "version_valid_until"))))
                    .toList(),
                "allow_degraded",
                allowDegraded));
    if (queryConfiguration != null)
      rerankRequest.put("model_configuration", queryConfiguration.runtime().rerank());
    long rerankStarted = System.nanoTime();
    Map<String, Object> ranked =
        evidence.isEmpty()
            ? Map.of("results", List.of(), "degraded", false)
            : worker.call("/internal/v1/rerank", rerankRequest);
    long rerankFinished = System.nanoTime();
    var byId =
        contexts.prepare(
            actor,
            evidence,
            () -> {
              reauthenticate(actor, authorization);
              for (var c : evidence) checkEvidence(actor, c, scope);
            });
    boolean debug = q.debug() && !actor.app() && !actor.role().equals("USER");
    var selection =
        EvidenceSelection.select(
            byId,
            (List<Map<String, Object>>) ranked.getOrDefault("results", List.of()),
            resultLimit,
            minimumScore,
            Boolean.TRUE.equals(ranked.get("degraded")),
            debug);
    reauthenticate(actor, authorization);
    for (var c : byId.values()) checkEvidence(actor, c, scope);
    var response = new LinkedHashMap<String, Object>();
    response.put("evidence", selection.evidence());
    response.put(
        "evidence_tokens",
        selection.evidence().stream().mapToLong(c -> num(c, "token_count")).sum());
    response.put("evidence_token_limit", 6000);
    response.put("evidence_tokenizer", "cl100k_base");
    response.put(
        "evidence_status",
        EvidenceSelection.status(
            selection,
            Boolean.TRUE.equals(recall.get("degraded"))
                || Boolean.TRUE.equals(ranked.get("degraded"))));
    response.put("minimum_rerank_score", minimumScore);
    response.put(
        "configuration_id", queryConfiguration == null ? "" : queryConfiguration.runtime().id());
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
      response.put("query_processing", processed);
      response.put(
          "timings_ms",
          Map.of(
              "recall", (recallFinished - recallStarted) / 1_000_000.0,
              "authorization", (rerankStarted - recallFinished) / 1_000_000.0,
              "rerank", (rerankFinished - rerankStarted) / 1_000_000.0,
              "evidence", (System.nanoTime() - rerankFinished) / 1_000_000.0,
              "total", (System.nanoTime() - started) / 1_000_000.0));
      response.put(
          "model_usage",
          Map.of(
              "embedding",
              recall.getOrDefault("usage", List.of()),
              "rerank",
              ranked.get("usage") == null
                  ? Map.of("state", evidence.isEmpty() ? "NOT_CALLED" : "UNKNOWN")
                  : ranked.get("usage")));
    }
    return response;
  }

  public Map<String, Object> generationRequest(
      Actor actor,
      Query query,
      Scope scope,
      List<Map<String, Object>> evidence,
      String authorization) {
    reauthenticate(actor, authorization);
    for (var item : evidence) checkEvidence(actor, item, scope);
    var configuration = scope.configuration();
    Map<String, Object> request =
        new LinkedHashMap<>(
            Map.of(
                "query",
                query.query(),
                "evidence",
                evidence.stream()
                    .map(
                        c ->
                            Map.of(
                                "id",
                                str(c, "id"),
                                "content",
                                str(c, "content"),
                                "title",
                                str(c, "title"),
                                "document_id",
                                str(c, "document_id"),
                                "version_id",
                                str(c, "version_id"),
                                "applicability",
                                Map.of(
                                    "document_from",
                                    str(c, "document_valid_from"),
                                    "document_until",
                                    str(c, "document_valid_until"),
                                    "version_from",
                                    str(c, "version_valid_from"),
                                    "version_until",
                                    str(c, "version_valid_until"))))
                    .toList()));
    if (configuration != null)
      request.put("model_configuration", configuration.runtime().generation());
    return request;
  }

  public void reauthenticate(Actor expected, String authorization) {
    Actor now = auth.authenticate(authorization);
    if (!expected.equals(now)) throw ApiException.hidden();
  }

  public void checkEvidence(Actor actor, Map<String, Object> evidence, Scope scope) {
    evidenceAuthorization.check(actor, evidence, scope);
  }

  public String saveAnswer(
      Actor actor, Query q, String content, List<Map<String, Object>> evidence) {
    return history.save(actor, q, content, evidence, null, null);
  }
}
