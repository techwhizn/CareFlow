package com.careflow.platform;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class QueryProcessingTest {
  @Test
  void whitespaceNormalizationPreservesIdentifiersPunctuationAndUnicode() {
    String source = "  CF-100\tE404\n型号 v2.1 /设备😀\u00a0 故障  ";
    var result = QueryProcessing.process(source);
    assertThat(result.original()).isEqualTo(source);
    assertThat(result.rewritten()).isEqualTo("CF-100 E404 型号 v2.1 /设备😀 故障");
    assertThat(result.identifiers()).containsExactly("CF-100", "E404", "v2.1");
    assertThat(QueryProcessing.process(result.rewritten()).rewritten())
        .isEqualTo(result.rewritten());
  }

  @Test
  void emptyAndOversizedQueriesCannotReachModels() {
    assertThatThrownBy(() -> QueryProcessing.process("\u00a0\t "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> QueryProcessing.process("a".repeat(4001)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
