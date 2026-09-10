package com.careflow.platform;

import static com.careflow.platform.Db.*;

import java.util.*;
import org.springframework.stereotype.Service;

/** Resolves only Java-authorized version IDs to their immutable processing configuration. */
@Service
public class ConfiguredModelRouting {
  private final Db db;
  private final KnowledgeConfigurationService configurations;
  private final WorkerClient worker;

  public ConfiguredModelRouting(
      Db db, KnowledgeConfigurationService configurations, WorkerClient worker) {
    this.db = db;
    this.configurations = configurations;
    this.worker = worker;
  }

  private record Group(String configuration, String identity, boolean generations) {}

  public record QueryConfiguration(
      KnowledgeConfiguration.Retrieval retrieval,
      KnowledgeConfiguration.RuntimeConfiguration runtime) {
    @Override
    public String toString() {
      return "QueryConfiguration[redacted]";
    }
  }

  public QueryConfiguration queryConfiguration(String tenant, List<String> versions) {
    QueryConfiguration selected = null;
    Set<String> checked = new HashSet<>();
    for (String version : versions) {
      var row =
          db.one(
              "SELECT k.published_configuration FROM document_versions v JOIN documents d ON d.id=v.document_id JOIN knowledge_bases k ON k.id=d.kb_id WHERE v.tenant_id=? AND v.id=?",
              tenant,
              version);
      String id = str(row, "published_configuration");
      if (id.isBlank() || !checked.add(id)) continue;
      var runtime = configurations.runtime(tenant, id);
      var next = new QueryConfiguration(configurations.definition(tenant, id).retrieval(), runtime);
      if (selected != null
          && (!selected.runtime().rerank().equals(next.runtime().rerank())
              || !selected.runtime().generation().equals(next.runtime().generation())
              || !selected.retrieval().equals(next.retrieval())))
        throw new ApiException(
            409, "QUERY_CONFIGURATION_CONFLICT", "所选知识库的查询配置不同，请选择一个知识库或使用配置一致的应用");
      selected = next;
    }
    return selected;
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> recall(
      String tenant, List<String> versions, String query, String mode, boolean degraded) {
    if (versions.isEmpty())
      return Map.of("dense", List.of(), "bm25", List.of(), "fused", List.of(), "degraded", false);
    Map<Group, List<String>> groups = new LinkedHashMap<>();
    Map<String, String> generations = new HashMap<>();
    for (String version : versions) {
      var row =
          db.one(
              "SELECT configuration_id,model_identity,active_index_generation FROM document_versions WHERE tenant_id=? AND id=?",
              tenant,
              version);
      String identity = str(row, "model_identity");
      if (identity.isBlank())
        throw new ApiException(503, "INDEX_CONFIGURATION_UNRESOLVED", "索引模型身份缺失，请核对原配置");
      String generation = str(row, "active_index_generation");
      if (!generation.isBlank()) generations.put(version, generation);
      groups
          .computeIfAbsent(
              new Group(str(row, "configuration_id"), identity, !generation.isBlank()),
              ignored -> new ArrayList<>())
          .add(version);
    }
    List<List<Map<String, Object>>> dense = new ArrayList<>(),
        bm25 = new ArrayList<>(),
        fused = new ArrayList<>();
    boolean anyDegraded = false;
    List<Map<String, Object>> usage = new ArrayList<>();
    for (var group : groups.entrySet()) {
      var configuration = configurations.runtime(tenant, group.getKey().configuration());
      Map<String, Object> request =
          new LinkedHashMap<>(
              Map.of(
                  "tenant_id",
                  tenant,
                  "version_ids",
                  group.getValue(),
                  "query",
                  query,
                  "mode",
                  mode,
                  "allow_degraded",
                  degraded,
                  "expected_model_identity",
                  group.getKey().identity()));
      if (configuration != null) request.put("model_configuration", configuration.embedding());
      if (group.getKey().generations())
        request.put("generation_ids", group.getValue().stream().map(generations::get).toList());
      var response = worker.call("/internal/v1/recall", request);
      usage.add(
          Map.of(
              "configuration_id",
              group.getKey().configuration(),
              "usage",
              response.get("usage") == null ? Map.of("state", "UNKNOWN") : response.get("usage")));
      dense.add((List<Map<String, Object>>) response.get("dense"));
      bm25.add((List<Map<String, Object>>) response.get("bm25"));
      fused.add((List<Map<String, Object>>) response.get("fused"));
      anyDegraded |= Boolean.TRUE.equals(response.get("degraded"));
    }
    if (groups.size() == 1)
      return Map.of(
          "dense",
          dense.getFirst(),
          "bm25",
          bm25.getFirst(),
          "fused",
          fused.getFirst(),
          "degraded",
          anyDegraded,
          "usage",
          usage);
    return Map.of(
        "dense",
        merge(dense, true),
        "bm25",
        merge(bm25, true),
        "fused",
        merge(fused, false),
        "degraded",
        anyDegraded,
        "usage",
        usage);
  }

  static List<Map<String, Object>> merge(
      List<List<Map<String, Object>>> lanes, boolean preserveScore) {
    Map<String, Double> ranks = new HashMap<>();
    Map<String, Map<String, Object>> originals = new HashMap<>();
    for (var lane : lanes) {
      Set<String> seen = new HashSet<>();
      int rank = 0;
      for (var hit : lane) {
        rank++;
        String id = str(hit, "id");
        if (seen.add(id)) {
          ranks.merge(id, 1.0 / (60 + rank), Double::sum);
          originals.putIfAbsent(id, hit);
        }
      }
    }
    return ranks.entrySet().stream()
        .sorted(
            Map.Entry.<String, Double>comparingByValue()
                .reversed()
                .thenComparing(Map.Entry.comparingByKey()))
        .limit(40)
        .map(
            entry ->
                preserveScore
                    ? originals.get(entry.getKey())
                    : Map.<String, Object>of("id", entry.getKey(), "score", entry.getValue()))
        .toList();
  }
}
