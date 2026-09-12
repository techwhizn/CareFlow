package com.careflow.platform;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class EvidenceSelectionTest {
  private Map<String, Object> candidate(String id, String document, String content) {
    return Map.of(
        "id",
        id,
        "document_id",
        document,
        "content",
        content,
        "source_text",
        "private source",
        "token_count",
        Math.max(1, content.length()));
  }

  @Test
  void publicStatusDistinguishesInsufficientRelevanceFromUnavailableScoring() {
    var candidates = Map.of("a", candidate("a", "d", "a"));
    List<Map<String, Object>> ranked = List.of(Map.of("id", "a", "score", .4));
    assertThat(
            EvidenceSelection.status(
                EvidenceSelection.select(candidates, ranked, 6, .5, false, false), false))
        .isEqualTo("BELOW_THRESHOLD");
    assertThat(
            EvidenceSelection.status(
                EvidenceSelection.select(candidates, ranked, 6, .5, true, false), true))
        .isEqualTo("SCORE_UNAVAILABLE");
    assertThat(
            EvidenceSelection.status(
                EvidenceSelection.select(candidates, ranked, 6, null, true, false), true))
        .isEqualTo("DEGRADED");
    assertThat(
            EvidenceSelection.status(
                EvidenceSelection.select(candidates, ranked, 6, .4, false, false), false))
        .isEqualTo("AVAILABLE");
    assertThat(
            EvidenceSelection.status(
                EvidenceSelection.select(candidates, List.of(), 6, null, false, false), false))
        .isEqualTo("NO_MATCH");
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
  void explicitTechnologyTermsMustAppearInEvidence() {
    var candidates = new LinkedHashMap<String, Map<String, Object>>();
    candidates.put("java", candidate("java", "d", "Java 后端开发经验"));
    candidates.put("php", candidate("php", "d", "PHP 项目开发经验"));
    var result =
        EvidenceSelection.select(
            candidates,
            List.of(Map.of("id", "java", "score", .9), Map.of("id", "php", "score", .8)),
            6,
            null,
            false,
            true,
            EvidenceSelection.explicitTerms("PHP 好学吗？"));
    assertThat(result.evidence()).extracting(row -> row.get("id")).containsExactly("php");
    assertThat(result.excluded())
        .containsExactly(new EvidenceSelection.Exclusion("java", "EXPLICIT_TERM_MISMATCH"));
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
      candidates.put(id, candidate(id, "d", id));
      ranked.add(Map.of("id", id, "score", 1));
    }
    candidates.put("long", candidate("long", "other", "a".repeat(6000)));
    ranked.add(Map.of("id", "long", "score", 1));
    ranked.add(Map.of("id", "c0", "score", 1));
    var result = EvidenceSelection.select(candidates, ranked, 6, null, false, true);
    assertThat(result.evidence()).hasSize(3);
    assertThat(result.excluded())
        .extracting(EvidenceSelection.Exclusion::reason)
        .containsExactly("DOCUMENT_LIMIT", "DOCUMENT_LIMIT", "CONTEXT_TOKEN_LIMIT");
    assertThat(EvidenceSelection.select(candidates, ranked, 1, null, false, false).evidence())
        .hasSize(1);
  }

  @Test
  void budgetUsesTokensRatherThanCharactersAndDeduplicatesIdenticalSources() {
    var english = new LinkedHashMap<>(candidate("english", "one", "word ".repeat(1500)));
    english.put("token_count", 1501);
    var chinese = new LinkedHashMap<>(candidate("chinese", "two", "复杂".repeat(1500)));
    chinese.put("token_count", 5000);
    var copy = new LinkedHashMap<>(english);
    copy.put("id", "copy");
    copy.put("document_id", "three");
    var selected =
        EvidenceSelection.select(
            Map.of("english", english, "chinese", chinese, "copy", copy),
            List.of(Map.of("id", "english"), Map.of("id", "copy"), Map.of("id", "chinese")),
            6,
            null,
            false,
            false);
    assertThat(selected.evidence()).extracting(row -> row.get("id")).containsExactly("english");
    assertThat(selected.excluded())
        .extracting(EvidenceSelection.Exclusion::reason)
        .containsExactly("DUPLICATE_CONTEXT", "CONTEXT_TOKEN_LIMIT");
  }
}
