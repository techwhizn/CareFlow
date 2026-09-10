package com.careflow.platform;

import static com.careflow.platform.Db.str;

import java.util.*;

/** Applies evidence limits only to candidates already authorized by RetrievalService. */
public final class EvidenceSelection {
  private EvidenceSelection() {}

  public record Exclusion(String id, String reason) {}

  public record Selection(List<Map<String, Object>> evidence, List<Exclusion> excluded) {}

  public static Selection select(
      Map<String, Map<String, Object>> authorized,
      List<Map<String, Object>> ranked,
      int limit,
      Double minimumScore,
      boolean degraded,
      boolean debug) {
    List<Map<String, Object>> result = new ArrayList<>();
    List<Exclusion> excluded = new ArrayList<>();
    Map<String, Integer> counts = new HashMap<>();
    Set<String> seen = new HashSet<>();
    int chars = 0;
    for (var hit : ranked) {
      String id = str(hit, "id");
      var candidate = authorized.get(id);
      if (candidate == null || !seen.add(id)) continue;
      Object rawScore = hit.get("score");
      Double score = rawScore instanceof Number n ? n.doubleValue() : null;
      String reason = null;
      if (minimumScore != null) {
        if (degraded || score == null || !Double.isFinite(score)) reason = "SCORE_UNAVAILABLE";
        else if (score < minimumScore) reason = "BELOW_MINIMUM_SCORE";
      }
      String document = str(candidate, "document_id");
      int length = str(candidate, "content").length();
      if (reason == null && result.size() >= Math.min(6, limit)) reason = "RESULT_LIMIT";
      if (reason == null && counts.getOrDefault(document, 0) >= 3) reason = "DOCUMENT_LIMIT";
      if (reason == null && chars + length > 6000) reason = "CONTEXT_LIMIT";
      if (reason != null) {
        excluded.add(new Exclusion(id, reason));
        continue;
      }
      chars += length;
      counts.merge(document, 1, Integer::sum);
      var output = new LinkedHashMap<>(candidate);
      output.remove("source_text");
      if (debug) output.put("rerank_score", score);
      result.add(output);
    }
    return new Selection(List.copyOf(result), List.copyOf(excluded));
  }
}
