package com.careflow.platform;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class CitationGuardTest {
  String id = "00000000-0000-0000-0000-000000000001";

  @Test
  void citationSplitAcrossEveryDeltaIsHeldUntilComplete() {
    var guard = new CitationGuard(List.of(id));
    assertThat(guard.accept("合成事实 [")).isEqualTo("合成事实 ");
    for (char c : id.toCharArray()) assertThat(guard.accept(String.valueOf(c))).isEmpty();
    assertThat(guard.accept("]。\n")).isEqualTo("[" + id + "]。\n");
    guard.finish();
    assertThat(guard.cited()).containsExactly(id);
  }

  @Test
  void inventedOldAndNumericReferencesNeverLeaveTheGuard() {
    for (String value : List.of("1", UUID.randomUUID().toString(), "untrusted", "[nested")) {
      var guard = new CitationGuard(List.of(id));
      assertThatThrownBy(() -> guard.accept("[" + value + "]"))
          .isInstanceOfSatisfying(
              ApiException.class, error -> assertThat(error.code).isEqualTo("INVALID_CITATION"));
    }
  }

  @Test
  void missingOrTruncatedCitationCannotFinishSuccessfully() {
    var missing = new CitationGuard(List.of(id));
    missing.accept("未引用的答案");
    assertThatThrownBy(missing::finish).isInstanceOf(ApiException.class);
    var truncated = new CitationGuard(List.of(id));
    truncated.accept("[" + id);
    assertThatThrownBy(truncated::finish).isInstanceOf(ApiException.class);
  }
}
