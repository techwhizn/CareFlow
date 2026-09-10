package com.careflow.platform;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ConfiguredModelRoutingTest {
  private Map<String, Object> hit(String id, double score) {
    return Map.of("id", id, "score", score);
  }

  @Test
  void mergesModelGroupsByRankRatherThanIncomparableRawScores() {
    var lanes = List.of(List.of(hit("a", .1), hit("b", .09)), List.of(hit("c", 10000)));
    var fused = ConfiguredModelRouting.merge(lanes, false);
    assertThat(fused).extracting(row -> row.get("id")).containsExactly("a", "c", "b");
    assertThat(fused.get(0).get("score")).isEqualTo(1.0 / 61);
    var diagnostic = ConfiguredModelRouting.merge(lanes, true);
    assertThat(diagnostic.get(1)).isEqualTo(hit("c", 10000));
  }

  @Test
  void duplicateWithinOneLaneDoesNotInflateItsRankAndOutputIsBounded() {
    var deduplicated =
        ConfiguredModelRouting.merge(
            List.of(List.of(hit("a", 1), hit("a", 1)), List.of(hit("b", 1))), false);
    assertThat(deduplicated).hasSize(2);
    assertThat(deduplicated.get(0).get("score")).isEqualTo(1.0 / 61);
    List<Map<String, Object>> many = new ArrayList<>();
    for (int i = 0; i < 60; i++) many.add(hit(String.valueOf(i), i));
    assertThat(ConfiguredModelRouting.merge(List.of(many), false)).hasSize(40);
  }
}
