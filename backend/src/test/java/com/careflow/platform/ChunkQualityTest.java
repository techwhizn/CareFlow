package com.careflow.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ChunkQualityTest extends ContentTestSupport {
  @Autowired ChunkQualityService service;

  @Test
  void reportsDuplicatesAcrossPagesAndUsesActualRowPageWithOrdinalGaps() {
    for (int i = 0; i < 251; i++) chunk(i * 2, "same synthetic content", 25, i != 250, "{}");
    var report = service.inspect(actor, version, 2);
    assertThat(report.chunks()).isEqualTo(251);
    assertThat(report.enabled_chunks()).isEqualTo(250);
    assertThat(report.tokens()).isEqualTo(6250);
    assertThat(report.warnings().get("DUPLICATE")).isEqualTo(251);
    assertThat(report.issues()).hasSize(51);
    assertThat(report.issues().getFirst().page()).isEqualTo(2);
    assertThat(report.issues().getFirst().ordinal()).isEqualTo(400);
    assertThat(report.has_more()).isFalse();
  }

  @Test
  void findsEmptyLongOcrBrokenTablesAndUpdatesWithoutStaleStoredFlags() {
    chunk(0, "", 0, true, "{}");
    String longId = chunk(1, "Long fixture", 601, true, "{}");
    chunk(2, "OCR \uFFFD", 5, true, "{\"warning\":\"OCR 结果需人工核对\"}");
    chunk(
        3,
        "Model: CF-100",
        22,
        true,
        "{\"type\":\"table\",\"quality_codes\":[\"TABLE_ROW_BROKEN\",\"TABLE_HEADER_DUPLICATE\"],\"block_start\":12}");
    var report = service.inspect(actor, version, 0);
    assertThat(report.warnings())
        .containsKeys(
            "EMPTY_CONTENT",
            "TOO_LONG",
            "TOO_SHORT",
            "OCR_REVIEW",
            "TEXT_ANOMALY",
            "TABLE_ROW_BROKEN",
            "TABLE_ROW_SPLIT",
            "TABLE_HEADER_MISSING");
    db.exec("UPDATE chunks SET token_count=30 WHERE id=?", longId);
    assertThat(service.inspect(actor, version, 0).warnings()).doesNotContainKey("TOO_LONG");
  }

  @Test
  void qualityApiHidesForeignVersionAndRejectsInvalidPage() throws Exception {
    mvc.perform(
            get("/api/v1/document-versions/" + Db.id() + "/quality").header("Authorization", token))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/api/v1/document-versions/" + version + "/quality?page=-1")
                .header("Authorization", token))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/api/v1/document-versions/" + version + "/quality").header("Authorization", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.chunks").value(0));
  }
}
