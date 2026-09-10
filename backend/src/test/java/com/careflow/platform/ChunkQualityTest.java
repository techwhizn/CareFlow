package com.careflow.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.careflow.platform.Identity.Actor;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:careflow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "careflow.scheduling=false",
      "careflow.model-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "careflow.internal-token=internal-testing-secret-at-least-32-chars",
      "careflow.bootstrap-token=bootstrap-testing-secret-at-least-32-chars"
    })
@AutoConfigureMockMvc
class ChunkQualityTest {
  @Autowired Db db;
  @Autowired Identity auth;
  @Autowired ChunkQualityService service;
  @Autowired MockMvc mvc;
  @MockitoBean BlobStore blobs;
  @MockitoBean WorkerClient worker;
  String tenant, version, token;
  Actor actor;

  @BeforeEach
  void setup() {
    tenant = Db.id();
    String member = Db.id(), kb = Db.id(), document = Db.id();
    version = Db.id();
    db.exec("INSERT INTO tenants(id,name) VALUES(?,'quality fixture')", tenant);
    db.exec(
        "INSERT INTO members(id,tenant_id,name,role) VALUES(?,?,'Owner','OWNER')", member, tenant);
    token = "Bearer " + auth.credential(tenant, member, "MEMBER", null);
    actor = auth.authenticate(token);
    db.exec(
        "INSERT INTO knowledge_bases(id,tenant_id,name,description,owner_id) VALUES(?,?,'Quality','',?)",
        kb,
        tenant,
        member);
    db.exec(
        "INSERT INTO documents(id,tenant_id,kb_id,title) VALUES(?,?,?,'Synthetic')",
        document,
        tenant,
        kb);
    db.exec(
        "INSERT INTO document_versions(id,tenant_id,document_id,object_key,filename,digest,state) VALUES(?,?,?,'fixture','fixture.csv','digest','PARSED')",
        version,
        tenant,
        document);
  }

  String chunk(int ordinal, String content, int tokens, boolean enabled, String location) {
    String id = Db.id();
    db.exec(
        "INSERT INTO chunks(id,tenant_id,version_id,ordinal_no,source_text,content,location,token_count,enabled) VALUES(?,?,?,?,?,?,?,?,?)",
        id,
        tenant,
        version,
        ordinal,
        content,
        content,
        location,
        tokens,
        enabled);
    return id;
  }

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
