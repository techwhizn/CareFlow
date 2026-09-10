package com.careflow.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.careflow.platform.Identity.Actor;
import com.careflow.platform.RetrievalService.Query;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:careflow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "careflow.scheduling=false",
      "careflow.internal-token=internal-testing-secret-at-least-32-chars",
      "careflow.bootstrap-token=bootstrap-testing-secret-at-least-32-chars"
    })
@AutoConfigureMockMvc
class PlatformBoundaryTest {
  @Autowired Db db;
  @Autowired Identity auth;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired Tasks tasks;
  @Autowired RetrievalService retrieval;
  @MockitoBean BlobStore blobs;
  @MockitoBean WorkerClient worker;
  String tenant, member, token, kb, document, version, chunk;
  Actor actor;

  @BeforeEach
  void setup() {
    tenant = Db.id();
    member = Db.id();
    kb = Db.id();
    document = Db.id();
    version = Db.id();
    chunk = Db.id();
    db.exec("INSERT INTO tenants(id,name) VALUES(?,?)", tenant, "Test");
    db.exec(
        "INSERT INTO members(id,tenant_id,name,role) VALUES(?,?,?,'OWNER')",
        member,
        tenant,
        "Owner");
    token = "Bearer " + auth.credential(tenant, member, "MEMBER", null);
    actor = auth.authenticate(token);
    db.exec(
        "INSERT INTO knowledge_bases(id,tenant_id,name,description,owner_id) VALUES(?,?,?,'',?)",
        kb,
        tenant,
        "Knowledge",
        member);
    db.exec(
        "INSERT INTO documents(id,tenant_id,kb_id,title,published_version) VALUES(?,?,?,?,?)",
        document,
        tenant,
        kb,
        "Manual",
        version);
    db.exec(
        "INSERT INTO document_versions(id,tenant_id,document_id,object_key,filename,digest,state,ever_published) VALUES(?,?,?,?,?,?,'READY',TRUE)",
        version,
        tenant,
        document,
        "key",
        "manual.txt",
        "digest");
    db.exec(
        "INSERT INTO chunks(id,tenant_id,version_id,ordinal_no,source_text,content,location,token_count) VALUES(?,?,?,0,'原文','测试知识','{}',4)",
        chunk,
        tenant,
        version);
  }

  @Test
  void requiresAuthentication() throws Exception {
    mvc.perform(get("/api/v1/knowledge-bases")).andExpect(status().isUnauthorized());
  }

  @Test
  void tenantCannotBeOverridden() throws Exception {
    String other = Db.id();
    mvc.perform(
            get("/api/v1/knowledge-bases/" + kb)
                .header("Authorization", token)
                .header("X-Tenant-ID", other))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/knowledge-bases/" + other).header("Authorization", token))
        .andExpect(status().isNotFound());
  }

  @Test
  void crossTenantObjectsAreHidden() throws Exception {
    String otherTenant = Db.id(), otherMember = Db.id();
    db.exec("INSERT INTO tenants(id,name) VALUES(?,'Other')", otherTenant);
    db.exec(
        "INSERT INTO members(id,tenant_id,name,role) VALUES(?,?,?,'OWNER')",
        otherMember,
        otherTenant,
        "Other");
    String otherToken = "Bearer " + auth.credential(otherTenant, otherMember, "MEMBER", null);
    for (String path :
        List.of(
            "/knowledge-bases/" + kb,
            "/documents/" + document + "/versions",
            "/document-versions/" + version + "/chunks",
            "/document-versions/" + version + "/source"))
      mvc.perform(get("/api/v1" + path).header("Authorization", otherToken))
          .andExpect(status().isNotFound());
  }

  @Test
  void publishedChunksCannotBeMutated() throws Exception {
    mvc.perform(
            put("/api/v1/chunks/" + chunk)
                .header("Authorization", token)
                .contentType("application/json")
                .content(
                    "{\"content\":\"changed\",\"enabled\":true,\"revision\":0,\"reason\":\"test\"}"))
        .andExpect(status().isConflict());
    assertThat(db.one("SELECT content FROM chunks WHERE id=?", chunk).get("content"))
        .isEqualTo("测试知识");
  }

  @Test
  void draftDoesNotChangePublishedVersion() throws Exception {
    mvc.perform(
            post("/api/v1/document-versions/" + version + "/draft").header("Authorization", token))
        .andExpect(status().isOk());
    assertThat(
            db.one("SELECT published_version FROM documents WHERE id=?", document)
                .get("published_version"))
        .isEqualTo(version);
    assertThat(db.list("SELECT id FROM document_versions WHERE document_id=?", document))
        .hasSize(2);
  }

  @Test
  void publicationChecksRevisionAndReadiness() throws Exception {
    mvc.perform(
            post("/api/v1/documents/" + document + "/publications")
                .header("Authorization", token)
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("version_id", version, "revision", 5))))
        .andExpect(status().isConflict());
    db.exec("UPDATE document_versions SET state='PARSED' WHERE id=?", version);
    mvc.perform(
            post("/api/v1/documents/" + document + "/publications")
                .header("Authorization", token)
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("version_id", version, "revision", 0))))
        .andExpect(status().isConflict());
  }

  @Test
  void documentAclNarrowsOwnerAccessAndHidesEntireAnswer() throws Exception {
    String answer = Db.id();
    db.exec(
        "INSERT INTO answers(id,tenant_id,subject_id,question,content) VALUES(?,?,?,'Question','Sensitive')",
        answer,
        tenant,
        member);
    db.exec(
        "INSERT INTO answer_evidence(answer_id,document_id,version_id,chunk_id) VALUES(?,?,?,?)",
        answer,
        document,
        version,
        chunk);
    mvc.perform(get("/api/v1/answers").header("Authorization", token))
        .andExpect(jsonPath("$[0].content").value("Sensitive"));
    mvc.perform(
            put("/api/v1/documents/" + document + "/permissions")
                .header("Authorization", token)
                .contentType("application/json")
                .content("{\"revision\":0,\"grants\":{}}"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/answers").header("Authorization", token))
        .andExpect(content().json("[]"));
    mvc.perform(
            get("/api/v1/document-versions/" + version + "/chunks").header("Authorization", token))
        .andExpect(status().isNotFound());
  }

  @Test
  void staleWorkerCannotResurrectDeletedDocument() throws Exception {
    String job = Db.id();
    db.exec("UPDATE document_versions SET state='QUEUED',ever_published=FALSE WHERE id=?", version);
    db.exec(
        "INSERT INTO jobs(id,tenant_id,version_id,kind,request_key) VALUES(?,?,?,'PARSE',?)",
        job,
        tenant,
        version,
        Db.id());
    var claimed = tasks.claim(job);
    mvc.perform(
            delete("/api/v1/documents/" + document + "?revision=0").header("Authorization", token))
        .andExpect(status().isOk());
    assertThatThrownBy(
            () -> tasks.complete(job, Db.str(claimed, "lease_token"), Map.of("chunks", List.of())))
        .isInstanceOf(ApiException.class);
    assertThat(db.one("SELECT status FROM documents WHERE id=?", document).get("status"))
        .isEqualTo("DELETED");
  }

  @Test
  void wrongLeaseCannotCompleteAndDuplicateClaimFails() {
    String job = Db.id();
    db.exec(
        "INSERT INTO jobs(id,tenant_id,version_id,kind,request_key) VALUES(?,?,?,'INDEX',?)",
        job,
        tenant,
        version,
        Db.id());
    tasks.claim(job);
    assertThatThrownBy(() -> tasks.claim(job)).isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () -> tasks.complete(job, Db.id(), Map.of("verified", true, "model_identity", "x")))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void duplicateUploadDoesNotCreateSecondJob() throws Exception {
    var file = new MockMultipartFile("file", "guide.txt", "text/plain", "new guide".getBytes());
    String key = Db.id();
    for (int i = 0; i < 2; i++)
      mvc.perform(
              multipart("/api/v1/knowledge-bases/" + kb + "/documents")
                  .file(file)
                  .header("Authorization", token)
                  .header("Idempotency-Key", key))
          .andExpect(status().isOk());
    assertThat(db.list("SELECT * FROM jobs WHERE tenant_id=? AND request_key=?", tenant, key))
        .hasSize(1);
  }

  @Test
  void applicationCannotBorrowMemberIdentity() {
    String app = Db.id();
    db.exec(
        "INSERT INTO applications(id,tenant_id,name,description,published) VALUES(?,?,?,'',TRUE)",
        app,
        tenant,
        "App");
    db.exec(
        "INSERT INTO application_bindings(tenant_id,application_id,kb_id) VALUES(?,?,?)",
        tenant,
        app,
        kb);
    Actor application = auth.authenticate("Bearer " + auth.credential(tenant, app, "APP", null));
    var q = new Query("测试", app, List.of(), "hybrid", 6, false, null);
    assertThat(retrieval.scope(application, q).versions()).isEmpty();
    db.exec(
        "INSERT INTO permissions(tenant_id,resource_id,subject_id,action) VALUES(?,?,?,'read')",
        tenant,
        kb,
        app);
    assertThat(retrieval.scope(application, q).versions()).containsExactly(version);
  }

  @Test
  void quotaReservationIsAtomicAndSettlementIdempotent() throws Exception {
    db.exec("UPDATE tenants SET query_limit=1 WHERE id=?", tenant);
    try (var pool = Executors.newFixedThreadPool(8)) {
      var futures = new ArrayList<Future<String>>();
      for (int i = 0; i < 8; i++)
        futures.add(
            pool.submit(
                () -> {
                  try {
                    return retrieval.reserve(actor, Db.id(), "");
                  } catch (ApiException e) {
                    return "rejected";
                  }
                }));
      List<String> successes = new ArrayList<>();
      for (var f : futures) {
        String r = f.get();
        if (!r.equals("rejected")) successes.add(r);
      }
      assertThat(successes).hasSize(1);
      retrieval.settle(actor, successes.getFirst(), true);
      retrieval.settle(actor, successes.getFirst(), true);
    }
    var quota = db.one("SELECT * FROM tenants WHERE id=?", tenant);
    assertThat(Db.num(quota, "queries_used")).isEqualTo(1);
    assertThat(Db.num(quota, "queries_reserved")).isZero();
  }

  @Test
  void revokedCredentialImmediatelyStopsAuthentication() {
    db.exec("UPDATE credentials SET active=FALSE WHERE tenant_id=?", tenant);
    assertThatThrownBy(() -> auth.authenticate(token)).isInstanceOf(ApiException.class);
  }

  @Test
  void internalRoutesRejectPublicTokens() throws Exception {
    mvc.perform(post("/internal/v1/jobs/" + Db.id() + "/claim").header("Authorization", token))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void viewerCannotReadUnpublishedDraftButCanDownloadGrantedPublication() throws Exception {
    String viewer = Db.id();
    db.exec(
        "INSERT INTO members(id,tenant_id,name,role) VALUES(?,?,?,'USER')",
        viewer,
        tenant,
        "Viewer");
    String bearer = "Bearer " + auth.credential(tenant, viewer, "MEMBER", null);
    for (String action : List.of("read", "download"))
      db.exec(
          "INSERT INTO permissions(tenant_id,resource_id,subject_id,action) VALUES(?,?,?,?)",
          tenant,
          kb,
          viewer,
          action);
    org.mockito.Mockito.when(blobs.get("key")).thenReturn("actual-source".getBytes());
    mvc.perform(
            get("/api/v1/document-versions/" + version + "/source").header("Authorization", bearer))
        .andExpect(status().isOk());
    db.exec("UPDATE document_versions SET ever_published=FALSE WHERE id=?", version);
    mvc.perform(
            get("/api/v1/document-versions/" + version + "/chunks").header("Authorization", bearer))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/documents/" + document + "/versions").header("Authorization", bearer))
        .andExpect(content().json("[]"));
  }

  @Test
  void configurationDraftDoesNotPublishAndRollbackRechecksCurrentGrants() throws Exception {
    String app = Db.id();
    db.exec(
        "INSERT INTO applications(id,tenant_id,name,description) VALUES(?,?,?,'')",
        app,
        tenant,
        "App");
    String body =
        json.writeValueAsString(
            Map.of("knowledge_base_ids", List.of(kb), "revision", 0, "allow_degraded", false));
    var response =
        mvc.perform(
                post("/api/v1/applications/" + app + "/configurations")
                    .header("Authorization", token)
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isOk())
            .andReturn();
    String configuration =
        json.readTree(response.getResponse().getContentAsString()).get("id").asText();
    assertThat(Db.bool(db.one("SELECT published FROM applications WHERE id=?", app), "published"))
        .isFalse();
    String publish =
        json.writeValueAsString(Map.of("configuration_id", configuration, "revision", 0));
    mvc.perform(
            post("/api/v1/applications/" + app + "/configuration-publications")
                .header("Authorization", token)
                .contentType("application/json")
                .content(publish))
        .andExpect(status().isConflict());
    db.exec(
        "INSERT INTO permissions(tenant_id,resource_id,subject_id,action) VALUES(?,?,?,'read')",
        tenant,
        kb,
        app);
    mvc.perform(
            post("/api/v1/applications/" + app + "/configuration-publications")
                .header("Authorization", token)
                .contentType("application/json")
                .content(publish))
        .andExpect(status().isOk());
    db.exec("DELETE FROM permissions WHERE tenant_id=? AND subject_id=?", tenant, app);
    String rollback =
        json.writeValueAsString(Map.of("configuration_id", configuration, "revision", 1));
    mvc.perform(
            post("/api/v1/applications/" + app + "/configuration-publications")
                .header("Authorization", token)
                .contentType("application/json")
                .content(rollback))
        .andExpect(status().isConflict());
  }

  @Test
  void editRecountsTokensPreservesSourceAndRejectsStaleRevision() throws Exception {
    db.exec("UPDATE document_versions SET ever_published=FALSE,state='PARSED' WHERE id=?", version);
    org.mockito.Mockito.when(
            worker.call(
                org.mockito.ArgumentMatchers.eq("/internal/v1/tokenize"),
                org.mockito.ArgumentMatchers.any()))
        .thenReturn(Map.of("token_count", 5));
    String body =
        json.writeValueAsString(
            Map.of("content", "修订内容", "enabled", true, "revision", 0, "reason", "boundary test"));
    mvc.perform(
            put("/api/v1/chunks/" + chunk)
                .header("Authorization", token)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk());
    var stored = db.one("SELECT * FROM chunks WHERE id=?", chunk);
    assertThat(Db.str(stored, "source_text")).isEqualTo("原文");
    assertThat(Db.num(stored, "token_count")).isEqualTo(5);
    assertThat(db.list("SELECT * FROM chunk_revisions WHERE chunk_id=?", chunk)).hasSize(1);
    mvc.perform(
            put("/api/v1/chunks/" + chunk)
                .header("Authorization", token)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isConflict());
  }

  @Test
  void searchAppliesThresholdToAuthorizedEvidenceAndExplainsExclusion() throws Exception {
    org.mockito.Mockito.when(
            worker.call(
                org.mockito.ArgumentMatchers.eq("/internal/v1/recall"),
                org.mockito.ArgumentMatchers.any()))
        .thenReturn(Map.of("fused", List.of(Map.of("id", chunk, "score", 1)), "degraded", false));
    org.mockito.Mockito.when(
            worker.call(
                org.mockito.ArgumentMatchers.eq("/internal/v1/rerank"),
                org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            Map.of("results", List.of(Map.of("id", chunk, "score", 0.2)), "degraded", false));
    mvc.perform(
            post("/api/v1/retrieval/search")
                .header("Authorization", token)
                .header("Idempotency-Key", Db.id())
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "query",
                            "test",
                            "mode",
                            "hybrid",
                            "limit",
                            6,
                            "debug",
                            true,
                            "minimum_rerank_score",
                            0.5))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.evidence").isEmpty())
        .andExpect(jsonPath("$.excluded[0].reason").value("BELOW_MINIMUM_SCORE"));
    var query = new Query("test", null, List.of(), "hybrid", 6, false, null);
    assertThat(
            (List<?>)
                retrieval
                    .search(actor, query, retrieval.scope(actor, query), token)
                    .get("evidence"))
        .hasSize(1);
    assertThatThrownBy(
            () ->
                retrieval.scope(
                    actor, new Query("test", null, List.of(), "hybrid", 6, false, Double.NaN)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void thresholdRefusalDoesNotCallGeneration() throws Exception {
    org.mockito.Mockito.when(
            worker.call(
                org.mockito.ArgumentMatchers.eq("/internal/v1/recall"),
                org.mockito.ArgumentMatchers.any()))
        .thenReturn(Map.of("fused", List.of(Map.of("id", chunk, "score", 1)), "degraded", false));
    org.mockito.Mockito.when(
            worker.call(
                org.mockito.ArgumentMatchers.eq("/internal/v1/rerank"),
                org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            Map.of("results", List.of(Map.of("id", chunk, "score", 0.2)), "degraded", false));
    var request =
        mvc.perform(
                post("/api/v1/answers")
                    .header("Authorization", token)
                    .header("Idempotency-Key", Db.id())
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "query",
                                "test",
                                "mode",
                                "hybrid",
                                "limit",
                                6,
                                "minimum_rerank_score",
                                0.5))))
            .andExpect(request().asyncStarted())
            .andReturn();
    request.getAsyncResult(5000);
    mvc.perform(asyncDispatch(request))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("event:done")));
    org.mockito.Mockito.verify(worker, org.mockito.Mockito.never()).stream(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    assertThat(db.list("SELECT * FROM answers WHERE tenant_id=?", tenant)).hasSize(1);
  }
}
