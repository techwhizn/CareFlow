package com.careflow.platform;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class EvidenceSelectionTest {
  private Map<String, Object> candidate(String id, String document, String content) {
    return Map.of(
        "id", id, "document_id", document, "content", content, "source_text", "private source");
  }

  @Test
  void thresholdIsInclusiveAndUnknownCandidatesAreNeverDisclosed() {
    var candidates = Map.of("a", candidate("a", "d", "a"), "b", candidate("b", "d", "b"));
    var result =
        EvidenceSelection.select(
            candidates,
            List.of(
                Map.of("id", "unknown", "score", 1),
                Map.of("id", "a", "score", .7),
                Map.of("id", "b", "score", .69)),
            6,
            .7,
            false,
            false);
    assertThat(result.evidence()).extracting(e -> e.get("id")).containsExactly("a");
    assertThat(result.evidence().getFirst()).doesNotContainKeys("source_text", "rerank_score");
    assertThat(result.excluded())
        .containsExactly(new EvidenceSelection.Exclusion("b", "BELOW_MINIMUM_SCORE"));
  }

  @Test
  void unavailableScoresCannotPassAnExplicitThreshold() {
    for (Object score : Arrays.asList(null, Double.NaN, Double.POSITIVE_INFINITY, "0.9")) {
      var hit = new HashMap<String, Object>();
      hit.put("id", "a");
      hit.put("score", score);
      var result =
          EvidenceSelection.select(
              Map.of("a", candidate("a", "d", "a")), List.of(hit), 6, 0.0, false, true);
      assertThat(result.evidence()).isEmpty();
      assertThat(result.excluded())
          .containsExactly(new EvidenceSelection.Exclusion("a", "SCORE_UNAVAILABLE"));
    }
  }

  @Test
  void degradedRankingCannotClaimConfidenceButRemainsAvailableWithoutThreshold() {
    var candidates = Map.of("a", candidate("a", "d", "a"));
    List<Map<String, Object>> ranked = List.of(Map.of("id", "a", "score", .9));
    assertThat(EvidenceSelection.select(candidates, ranked, 6, .1, true, false).evidence())
        .isEmpty();
    assertThat(EvidenceSelection.select(candidates, ranked, 6, null, true, false).evidence())
        .hasSize(1);
  }

  @Test
  void duplicateDocumentAndContextLimitsRemainEnforced() {
    var candidates = new LinkedHashMap<String, Map<String, Object>>();
    List<Map<String, Object>> ranked = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      String id = "c" + i;
      candidates.put(id, candidate(id, "d", "a"));
      ranked.add(Map.of("id", id, "score", 1));
    }
    candidates.put("long", candidate("long", "other", "a".repeat(6000)));
    ranked.add(Map.of("id", "long", "score", 1));
    ranked.add(Map.of("id", "c0", "score", 1));
    var result = EvidenceSelection.select(candidates, ranked, 6, null, false, true);
    assertThat(result.evidence()).hasSize(3);
    assertThat(result.excluded())
        .extracting(EvidenceSelection.Exclusion::reason)
        .containsExactly("DOCUMENT_LIMIT", "DOCUMENT_LIMIT", "CONTEXT_LIMIT");
    assertThat(EvidenceSelection.select(candidates, ranked, 1, null, false, false).evidence())
        .hasSize(1);
  }
}
