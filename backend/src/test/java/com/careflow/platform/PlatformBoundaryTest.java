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
      "careflow.model-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
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
  @Autowired ModelProfileService modelProfiles;
  @Autowired KnowledgeConfigurationService configurations;
  @Autowired UploadStaging staging;
  @Autowired RetrievalService retrieval;
  @MockitoBean BlobStore blobs;
  @MockitoBean WorkerClient worker;
  String tenant, member, token, kb, document, version, chunk;
  Actor actor;

  @BeforeEach
  void setup() {
    org.mockito.Mockito.when(
            worker.call(
                org.mockito.ArgumentMatchers.eq("/internal/v1/context/tokens"),
                org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            call -> {
              var request = call.getArgument(1, WorkerProtocolV1.ContextTokensRequest.class);
              return Map.of(
                  "tokenizer",
                  "cl100k_base",
                  "counts",
                  request.candidates().stream()
                      .map(c -> Map.of("id", c.id(), "token_count", 4))
                      .toList());
            });
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
        "UPDATE document_versions SET model_identity='synthetic-index-identity' WHERE id=?",
        version);
    db.exec(
        "INSERT INTO chunks(id,tenant_id,version_id,ordinal_no,source_text,content,location,token_count) VALUES(?,?,?,0,'原文','测试知识','{}',4)",
        chunk,
        tenant,
        version);
  }

  @Autowired EntitlementService entitlements;

  @Test
  void recallBindsAuthoritativeIndexIdentityAndUnknownIdentityCannotReturnEmptySuccess()
      throws Exception {
    org.mockito.Mockito.when(
            worker.call(
                org.mockito.ArgumentMatchers.eq("/internal/v1/recall"),
                org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            Map.of("dense", List.of(), "bm25", List.of(), "fused", List.of(), "degraded", false));
    Query query = new Query("synthetic", null, List.of(kb), "hybrid", 6, false, null);
    var result = retrieval.search(actor, query, retrieval.scope(actor, query), token);
    assertThat(result.get("evidence")).isEqualTo(List.of());
    org.mockito.ArgumentCaptor<Object> request = org.mockito.ArgumentCaptor.forClass(Object.class);
    org.mockito.Mockito.verify(worker)
        .call(org.mockito.ArgumentMatchers.eq("/internal/v1/recall"), request.capture());
    assertThat(((Map<?, ?>) request.getValue()).get("expected_model_identity"))
        .isEqualTo("synthetic-index-identity");
    org.mockito.Mockito.clearInvocations(worker);
    db.exec("UPDATE document_versions SET model_identity=NULL WHERE id=?", version);
    assertThatThrownBy(() -> retrieval.search(actor, query, retrieval.scope(actor, query), token))
        .isInstanceOfSatisfying(
            ApiException.class,
            error -> assertThat(error.code).isEqualTo("INDEX_CONFIGURATION_UNRESOLVED"));
    org.mockito.Mockito.verifyNoInteractions(worker);
  }

  @Test
  void metadataFiltersNarrowRecallAndNeverWidenDocumentAuthorization() {
    var matches =
        new MetadataFilters.Rule(
            MetadataFilters.Field.language, MetadataFilters.Operator.eq, json.valueToTree("zh"));
    var miss =
        new MetadataFilters.Rule(
            MetadataFilters.Field.language,
            MetadataFilters.Operator.eq,
            json.valueToTree("no-match"));
    var query =
        new Query("fixture", null, List.of(kb), "keyword", 6, false, null, List.of(matches));
    assertThat(retrieval.scope(actor, query).versions()).containsExactly(version);
    var excluded =
        new Query("fixture", null, List.of(kb), "keyword", 6, false, null, List.of(miss));
    var scope = retrieval.scope(actor, excluded);
    assertThat(scope.versions()).isEmpty();
    assertThat((List<?>) retrieval.search(actor, excluded, scope, token).get("evidence")).isEmpty();
    org.mockito.Mockito.verifyNoInteractions(worker);
    db.exec("UPDATE documents SET restricted=TRUE WHERE id=?", document);
    assertThat(retrieval.scope(actor, query).versions()).isEmpty();
  }

  @Test
  void invalidFilterFieldsAndValuesFailBeforeModelCallsOrQuotaReservation() throws Exception {
    for (var filter :
        List.of(
            Map.of("field", "tenant_id", "operator", "eq", "value", tenant),
            Map.of("field", "language", "operator", "eq", "value", 7),
            Map.of("field", "valid_from", "operator", "gte", "value", "2026-09-10"))) {
      mvc.perform(
              post("/api/v1/retrieval/search")
                  .header("Authorization", token)
                  .header("Idempotency-Key", Db.id())
                  .contentType("application/json")
                  .content(
                      json.writeValueAsBytes(
                          Map.of(
                              "query",
                              "fixture",
                              "mode",
                              "keyword",
                              "limit",
                              6,
                              "filters",
                              List.of(filter)))))
          .andExpect(status().isBadRequest());
    }
    org.mockito.Mockito.verifyNoInteractions(worker);
    assertThat(db.list("SELECT id FROM usage_events WHERE tenant_id=?", tenant)).isEmpty();
  }

  @Test
  void publicationRejectsUnseenContentRevisionEvenAfterReindexing() throws Exception {
    org.mockito.Mockito.when(
            worker.call(
                org.mockito.ArgumentMatchers.eq("/internal/v1/tokenize"),
                org.mockito.ArgumentMatchers.anyMap()))
        .thenReturn(Map.of("token_count", 3));
    var result =
        mvc.perform(
                post("/api/v1/document-versions/" + version + "/draft")
                    .header("Authorization", token))
            .andReturn();
    String draft = json.readTree(result.getResponse().getContentAsString()).get("id").asText();
    String copied = Db.str(db.one("SELECT id FROM chunks WHERE version_id=?", draft), "id");
    mvc.perform(
            put("/api/v1/chunks/" + copied)
                .header("Authorization", token)
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of(
                            "content",
                            "Revised synthetic knowledge",
                            "enabled",
                            true,
                            "revision",
                            0,
                            "reason",
                            "Synthetic revision test"))))
        .andExpect(status().isOk());
    // This component test stands in for successful real indexing; it is not RAG acceptance.
    db.exec("UPDATE document_versions SET state='READY' WHERE id=?", draft);
    String endpoint = "/api/v1/documents/" + document + "/publications";
    mvc.perform(
            post(endpoint)
                .header("Authorization", token)
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of("version_id", draft, "revision", 0, "version_revision", 0))))
        .andExpect(status().isConflict());
    assertThat(
            Db.str(
                db.one("SELECT published_version FROM documents WHERE id=?", document),
                "published_version"))
        .isEqualTo(version);
    mvc.perform(
            post(endpoint)
                .header("Authorization", token)
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of("version_id", draft, "revision", 0, "version_revision", 1))))
        .andExpect(status().isOk());
    mvc.perform(get(endpoint).header("Authorization", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].previous_version").value(version))
        .andExpect(jsonPath("$[0].version_revision").value(1))
        .andExpect(jsonPath("$[0].document_revision").value(1));
    mvc.perform(
            post(endpoint)
                .header("Authorization", token)
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        Map.of("version_id", version, "revision", 1, "version_revision", 0))))
        .andExpect(status().isOk());
    assertThat(Db.str(db.one("SELECT content FROM chunks WHERE id=?", chunk), "content"))
        .isEqualTo("测试知识");
    assertThat(db.list("SELECT id FROM publications WHERE document_id=?", document)).hasSize(2);
  }

  @Test
  void missingContentRevisionAndProcessingDraftSourceAreRejected() throws Exception {
    mvc.perform(
            post("/api/v1/documents/" + document + "/publications")
                .header("Authorization", token)
                .contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("version_id", version, "revision", 0))))
        .andExpect(status().isBadRequest());
    db.exec("UPDATE document_versions SET state='PARSING' WHERE id=?", version);
    mvc.perform(
            post("/api/v1/document-versions/" + version + "/draft").header("Authorization", token))
        .andExpect(status().isConflict());
    assertThat(db.list("SELECT id FROM document_versions WHERE document_id=?", document))
        .hasSize(1);
  }

  @Test
  void simultaneousPublicationsCannotSilentlyOverwriteEachOther() throws Exception {
    String endpoint = "/api/v1/documents/" + document + "/publications";
    byte[] input =
        json.writeValueAsBytes(Map.of("version_id", version, "revision", 0, "version_revision", 0));
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<Integer> publish =
          () ->
              mvc.perform(
                      post(endpoint)
                          .header("Authorization", token)
                          .contentType("application/json")
                          .content(input))
                  .andReturn()
                  .getResponse()
                  .getStatus();
      var first = pool.submit(publish);
      var second = pool.submit(publish);
      assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 409);
    }
    assertThat(db.list("SELECT id FROM publications WHERE document_id=?", document)).hasSize(1);
  }

  @Test
  void replacementUploadKeepsOldPublicationAndItsImmutableChunks() throws Exception {
    mvc.perform(
            multipart("/api/v1/documents/" + document + "/versions")
                .file(
                    new MockMultipartFile(
                        "file",
                        "replacement.txt",
                        "text/plain",
                        "Synthetic replacement".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .header("Authorization", token)
                .header("Idempotency-Key", Db.id()))
        .andExpect(status().isOk());
    assertThat(db.list("SELECT id FROM document_versions WHERE document_id=?", document))
        .hasSize(2);
    assertThat(
            Db.str(
                db.one("SELECT published_version FROM documents WHERE id=?", document),
                "published_version"))
        .isEqualTo(version);
    assertThat(Db.str(db.one("SELECT content FROM chunks WHERE id=?", chunk), "content"))
        .isEqualTo("测试知识");
  }

  @Test
  void entitlementUpdatesValidatePeriodRevisionAndWarnWithoutResettingUsage() throws Exception {
    var snapshot = entitlements.snapshot(actor);
    assertThat(snapshot.unknown_source_objects()).isEqualTo(1);
    assertThat(snapshot.resources().get("storage_bytes").used()).isEqualTo(52428800);
    var input = json.convertValue(snapshot.configuration(), Map.class);
    input.put("reason", "Synthetic quota test");
    input.put("member_limit", 1);
    input.put("active", false);
    mvc.perform(
            put("/api/v1/entitlement")
                .header("Authorization", token)
                .contentType("application/json")
                .content(json.writeValueAsBytes(input)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(false))
        .andExpect(jsonPath("$.resources.members.warning").value(true));
    mvc.perform(
            put("/api/v1/entitlement")
                .header("Authorization", token)
                .contentType("application/json")
                .content(json.writeValueAsBytes(input)))
        .andExpect(status().isConflict());
    assertThatThrownBy(() -> retrieval.reserve(actor, Db.id(), ""))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("套餐");
    input.put("revision", 1);
    input.put("active", true);
    input.put("starts_at", "2027-01-01T00:00:00Z");
    input.put("expires_at", "2026-01-01T00:00:00Z");
    mvc.perform(
            put("/api/v1/entitlement")
                .header("Authorization", token)
                .contentType("application/json")
                .content(json.writeValueAsBytes(input)))
        .andExpect(status().isBadRequest());
    input.put("starts_at", null);
    input.put("expires_at", "2000-01-01T00:00:00Z");
    mvc.perform(
            put("/api/v1/entitlement")
                .header("Authorization", token)
                .contentType("application/json")
                .content(json.writeValueAsBytes(input)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(false));
    input.put("revision", 2);
    input.put("expires_at", null);
    mvc.perform(
            put("/api/v1/entitlement")
                .header("Authorization", token)
                .contentType("application/json")
                .content(json.writeValueAsBytes(input)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(true));
    String user = Db.id();
    db.exec(
        "INSERT INTO members(id,tenant_id,name,role) VALUES(?,?,?,'USER')", user, tenant, "Reader");
    mvc.perform(
            get("/api/v1/entitlement")
                .header("Authorization", "Bearer " + auth.credential(tenant, user, "MEMBER", null)))
        .andExpect(status().isNotFound());
  }

  @Test
  void resourceLimitsRejectBeforeCreatingMembersBasesOrUploadingBytes() throws Exception {
    db.exec(
        "UPDATE tenants SET member_limit=1,knowledge_base_limit=1,storage_limit_bytes=52428800 WHERE id=?",
        tenant);
    mvc.perform(
            post("/api/v1/members")
                .header("Authorization", token)
                .contentType("application/json")
                .content("{\"name\":\"Synthetic\",\"role\":\"USER\"}"))
        .andExpect(status().isTooManyRequests());
    mvc.perform(
            post("/api/v1/knowledge-bases")
                .header("Authorization", token)
                .contentType("application/json")
                .content("{\"name\":\"Synthetic\",\"description\":\"\"}"))
        .andExpect(status().isTooManyRequests());
    mvc.perform(
            multipart("/api/v1/knowledge-bases/" + kb + "/documents")
                .file(new MockMultipartFile("file", "quota.txt", "text/plain", new byte[] {1}))
                .header("Authorization", token)
                .header("Idempotency-Key", Db.id()))
        .andExpect(status().isTooManyRequests());
    org.mockito.Mockito.verifyNoInteractions(blobs);
  }

  @Test
  void taskQuotaCountsFirstClaimOnlyAndConcurrencyPreventsAnotherLease() {
    db.exec(
        "UPDATE tenants SET processing_limit=1,task_concurrency_limit=1,pdf_page_limit=2 WHERE id=?",
        tenant);
    String first = Db.id(), second = Db.id();
    for (String job : List.of(first, second))
      db.exec(
          "INSERT INTO jobs(id,tenant_id,version_id,kind,request_key) VALUES(?,?,?,'PARSE',?)",
          job,
          tenant,
          version,
          Db.id());
    assertThat(Db.num(tasks.claim(first), "pdf_page_limit")).isEqualTo(2);
    assertThatThrownBy(() -> tasks.claim(second)).isInstanceOf(ApiException.class);
    db.exec(
        "UPDATE jobs SET lease_until=? WHERE id=?",
        java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(10)),
        first);
    tasks.recover();
    tasks.claim(first);
    assertThat(
            Db.num(
                db.one("SELECT processing_used FROM tenants WHERE id=?", tenant),
                "processing_used"))
        .isEqualTo(1);
    tasks.cancel(actor, first);
    assertThatThrownBy(() -> tasks.claim(second)).isInstanceOf(ApiException.class);
    assertThat(Db.num(db.one("SELECT attempts FROM jobs WHERE id=?", second), "attempts")).isZero();
  }

  @Test
  void queryConcurrencyIsAtomicAndReleasesOnFailure() throws Exception {
    db.exec("UPDATE tenants SET query_concurrency_limit=1 WHERE id=?", tenant);
    try (var pool = Executors.newFixedThreadPool(4)) {
      var calls = new ArrayList<Future<String>>();
      for (int i = 0; i < 4; i++)
        calls.add(
            pool.submit(
                () -> {
                  try {
                    return retrieval.reserve(actor, Db.id(), "");
                  } catch (ApiException e) {
                    return "rejected";
                  }
                }));
      var accepted = new ArrayList<String>();
      for (var call : calls) {
        String value = call.get();
        if (!value.equals("rejected")) accepted.add(value);
      }
      assertThat(accepted).hasSize(1);
      retrieval.settle(actor, accepted.getFirst(), false);
      String next = retrieval.reserve(actor, Db.id(), "");
      retrieval.settle(actor, next, false);
      assertThat(
              Db.num(db.one("SELECT queries_used FROM tenants WHERE id=?", tenant), "queries_used"))
          .isZero();
    }
  }

  @Test
  void archiveRestoreAndDeleteKeepAuthorizationAndEnqueueCleanup() throws Exception {
    var query = new Query("fixture", null, List.of(kb), "keyword", 6, false, null);
    mvc.perform(get("/api/v1/knowledge-bases/" + kb + "/impact").header("Authorization", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.visible_documents").value(1));
    mvc.perform(
            put("/api/v1/knowledge-bases/" + kb + "/state")
                .header("Authorization", token)
                .contentType("application/json")
                .content("{\"status\":\"ARCHIVED\",\"revision\":0}"))
        .andExpect(status().isOk());
    assertThat(retrieval.scope(actor, query).versions()).isEmpty();
    mvc.perform(
            put("/api/v1/knowledge-bases/" + kb + "/state")
                .header("Authorization", token)
                .contentType("application/json")
                .content("{\"status\":\"ACTIVE\",\"revision\":0}"))
        .andExpect(status().isConflict());
    mvc.perform(
            put("/api/v1/knowledge-bases/" + kb + "/state")
                .header("Authorization", token)
                .contentType("application/json")
                .content("{\"status\":\"ACTIVE\",\"revision\":1}"))
        .andExpect(status().isOk());
    assertThat(retrieval.scope(actor, query).versions()).contains(version);
    mvc.perform(
            put("/api/v1/knowledge-bases/" + kb + "/state")
                .header("Authorization", token)
                .contentType("application/json")
                .content("{\"status\":\"DELETED\",\"revision\":2}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cleanup_request_id").isNotEmpty());
    assertThat(
            db.list(
                "SELECT id FROM cleanup_requests WHERE tenant_id=? AND resource_id=? AND state='PENDING'",
                tenant,
                kb))
        .hasSize(1);
    mvc.perform(get("/api/v1/knowledge-bases/" + kb).header("Authorization", token))
        .andExpect(status().isNotFound());
    mvc.perform(
            put("/api/v1/knowledge-bases/" + kb + "/state")
                .header("Authorization", token)
                .contentType("application/json")
                .content("{\"status\":\"ACTIVE\",\"revision\":3}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void archiveCancelsRunningWorkAndImpactDoesNotCountRestrictedDocuments() throws Exception {
    String job = Db.id();
    db.exec(
        "INSERT INTO jobs(id,tenant_id,version_id,kind,request_key) VALUES(?,?,?,'INDEX',?)",
        job,
        tenant,
        version,
        Db.id());
    bindIndexJob(job);
    String lease = Db.str(tasks.claim(job), "lease_token");
    String hidden = Db.id();
    db.exec(
        "INSERT INTO documents(id,tenant_id,kb_id,title,restricted) VALUES(?,?,?,'Hidden',TRUE)",
        hidden,
        tenant,
        kb);
    mvc.perform(get("/api/v1/knowledge-bases/" + kb + "/impact").header("Authorization", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.visible_documents").value(1));
    mvc.perform(
            put("/api/v1/knowledge-bases/" + kb + "/state")
                .header("Authorization", token)
                .contentType("application/json")
                .content("{\"status\":\"ARCHIVED\",\"revision\":0}"))
        .andExpect(status().isOk());
    assertThat(Db.str(db.one("SELECT state FROM jobs WHERE id=?", job), "state"))
        .isEqualTo("CANCELLED");
    assertThatThrownBy(
            () -> tasks.complete(job, lease, Map.of("verified", true, "model_identity", "fixture")))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void lifecycleRejectsUnknownStateAndRestoreWithoutEnabledResponsibleMember() throws Exception {
    mvc.perform(
            put("/api/v1/knowledge-bases/" + kb + "/state")
                .header("Authorization", token)
                .contentType("application/json")
                .content("{\"revision\":0}"))
        .andExpect(status().isBadRequest());
    String disabled = Db.id();
    db.exec(
        "INSERT INTO members(id,tenant_id,name,role,active) VALUES(?,?,'Disabled','KNOWLEDGE_MANAGER',FALSE)",
        disabled,
        tenant);
    db.exec("UPDATE knowledge_bases SET owner_id=?,status='ARCHIVED' WHERE id=?", disabled, kb);
    db.exec(
        "INSERT INTO permissions(tenant_id,resource_id,subject_id,action) VALUES(?,?,?,'manage')",
        tenant,
        kb,
        member);
    mvc.perform(
            put("/api/v1/knowledge-bases/" + kb + "/state")
                .header("Authorization", token)
                .contentType("application/json")
                .content("{\"status\":\"ACTIVE\",\"revision\":0}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("RESOURCE_OWNER_REQUIRED"));
  }

  @Test
  void uploadStorageRunsOutsideTransactionAndRevocationPreventsAttachment() throws Exception {
    org.mockito.Mockito.doAnswer(
            invocation -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              db.exec("UPDATE credentials SET active=FALSE WHERE tenant_id=?", tenant);
              return null;
            })
        .when(blobs)
        .put(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(byte[].class));
    mvc.perform(
            multipart("/api/v1/knowledge-bases/" + kb + "/documents")
                .file(
                    new MockMultipartFile(
                        "file", "revoked.txt", "text/plain", "Synthetic revocation".getBytes()))
                .header("Authorization", token)
                .header("Idempotency-Key", Db.id()))
        .andExpect(status().isUnauthorized());
    assertThat(db.list("SELECT id FROM jobs WHERE tenant_id=?", tenant)).isEmpty();
    assertThat(db.list("SELECT id FROM documents WHERE tenant_id=?", tenant)).hasSize(1);
    var pending = db.one("SELECT * FROM upload_staging WHERE tenant_id=?", tenant);
    db.exec(
        "UPDATE upload_staging SET expires_at=? WHERE id=?",
        java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(10)),
        Db.str(pending, "id"));
    staging.cleanup();
    org.mockito.Mockito.verify(blobs).delete(Db.str(pending, "object_key"));
    assertThat(
            Db.str(
                db.one("SELECT state FROM upload_staging WHERE id=?", Db.str(pending, "id")),
                "state"))
        .isEqualTo("DELETED");
  }

  @Test
  void failedStorageLeavesRetryableCleanupButNeverPublishesDocument() throws Exception {
    org.mockito.Mockito.doThrow(new ApiException(503, "STORAGE_UNAVAILABLE", "Failure"))
        .when(blobs)
        .put(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(byte[].class));
    mvc.perform(
            multipart("/api/v1/knowledge-bases/" + kb + "/documents")
                .file(
                    new MockMultipartFile(
                        "file", "failed.txt", "text/plain", "Synthetic failure".getBytes()))
                .header("Authorization", token)
                .header("Idempotency-Key", Db.id()))
        .andExpect(status().isServiceUnavailable());
    var stage = db.one("SELECT * FROM upload_staging WHERE tenant_id=?", tenant);
    String key = Db.str(stage, "object_key"), id = Db.str(stage, "id");
    db.exec(
        "UPDATE upload_staging SET expires_at=? WHERE id=?",
        java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(10)),
        id);
    org.mockito.Mockito.doThrow(new ApiException(503, "STORAGE_UNAVAILABLE", "Failure"))
        .doNothing()
        .when(blobs)
        .delete(key);
    staging.cleanup();
    assertThat(Db.str(db.one("SELECT state FROM upload_staging WHERE id=?", id), "state"))
        .isEqualTo("CLEANING");
    db.exec(
        "UPDATE upload_staging SET expires_at=? WHERE id=?",
        java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(10)),
        id);
    staging.cleanup();
    assertThat(Db.str(db.one("SELECT state FROM upload_staging WHERE id=?", id), "state"))
        .isEqualTo("DELETED");
    assertThat(db.list("SELECT id FROM jobs WHERE tenant_id=?", tenant)).isEmpty();
  }

  @Test
  void attachedUploadSurvivesCleanupAndExactReplayDoesNotStoreAgain() throws Exception {
    String key = Db.id();
    var file =
        new MockMultipartFile(
            "file", "once.txt", "text/plain", "Synthetic exact replay".getBytes());
    String first =
        mvc.perform(
                multipart("/api/v1/knowledge-bases/" + kb + "/documents")
                    .file(file)
                    .header("Authorization", token)
                    .header("Idempotency-Key", key))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    mvc.perform(
            multipart("/api/v1/knowledge-bases/" + kb + "/documents")
                .file(file)
                .header("Authorization", token)
                .header("Idempotency-Key", key))
        .andExpect(status().isOk())
        .andExpect(content().json(first));
    var stage = db.one("SELECT * FROM upload_staging WHERE tenant_id=?", tenant);
    db.exec(
        "UPDATE upload_staging SET expires_at=? WHERE id=?",
        java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(10)),
        Db.str(stage, "id"));
    staging.cleanup();
    org.mockito.Mockito.verify(blobs, org.mockito.Mockito.times(1))
        .put(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(byte[].class));
    org.mockito.Mockito.verify(blobs, org.mockito.Mockito.never())
        .delete(Db.str(stage, "object_key"));
  }

  @Test
  void checkpointsHeartbeatAndCancellationFenceLateCallbacks() {
    String job = Db.id();
    db.exec("UPDATE document_versions SET ever_published=FALSE WHERE id=?", version);
    db.exec(
        "INSERT INTO jobs(id,tenant_id,version_id,kind,request_key) VALUES(?,?,?,'PARSE',?)",
        job,
        tenant,
        version,
        Db.id());
    String lease = Db.str(tasks.claim(job), "lease_token");
    tasks.checkpoint(job, lease, "SOURCE_READY");
    tasks.heartbeat(job, lease);
    assertThat(Db.str(db.one("SELECT checkpoint FROM jobs WHERE id=?", job), "checkpoint"))
        .isEqualTo("SOURCE_READY");
    assertThatThrownBy(() -> tasks.checkpoint(job, lease, "STARTED"))
        .isInstanceOf(IllegalArgumentException.class);
    tasks.cancel(actor, job);
    assertThatThrownBy(() -> tasks.heartbeat(job, lease)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> tasks.complete(job, lease, Map.of("chunks", List.of())))
        .isInstanceOf(ApiException.class);
    assertThat(Db.str(db.one("SELECT state FROM jobs WHERE id=?", job), "state"))
        .isEqualTo("CANCELLED");
  }

  @Test
  void leaseRecoveryStopsAfterThreeAttemptsAndTerminalFailuresNeverRetry() {
    String job = Db.id();
    db.exec(
        "INSERT INTO jobs(id,tenant_id,version_id,kind,request_key) VALUES(?,?,?,'PARSE',?)",
        job,
        tenant,
        version,
        Db.id());
    for (int attempt = 1; attempt <= 3; attempt++) {
      String lease = Db.str(tasks.claim(job), "lease_token");
      db.exec(
          "UPDATE jobs SET lease_until=? WHERE id=?",
          java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(10)),
          job);
      tasks.recover();
      tasks.recover();
      assertThatThrownBy(() -> tasks.heartbeat(job, lease)).isInstanceOf(ApiException.class);
    }
    assertThat(Db.str(db.one("SELECT state FROM jobs WHERE id=?", job), "state"))
        .isEqualTo("FAILED");
    assertThat(db.list("SELECT id FROM outbox WHERE job_id=?", job)).hasSize(2);
    String terminal = Db.id();
    db.exec(
        "INSERT INTO jobs(id,tenant_id,version_id,kind,request_key) VALUES(?,?,?,'PARSE',?)",
        terminal,
        tenant,
        version,
        Db.id());
    String lease = Db.str(tasks.claim(terminal), "lease_token");
    tasks.failed(terminal, lease, "INVALID_FILE", true);
    assertThat(Db.str(db.one("SELECT state FROM jobs WHERE id=?", terminal), "state"))
        .isEqualTo("FAILED");
    assertThat(db.list("SELECT id FROM outbox WHERE job_id=?", terminal)).isEmpty();
  }

  @Test
  void metadataDatesNormalizeAndChangesHideExpiredEvidenceAndHistory() throws Exception {
    var q = new Query("test", null, List.of(kb), "keyword", 6, false, null);
    var evidence =
        Map.<String, Object>of("id", chunk, "document_id", document, "version_id", version);
    String answer = retrieval.saveAnswer(actor, q, "Synthetic", List.of(evidence));
    assertThat(retrieval.scope(actor, q).versions()).contains(version);
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("title", "Updated document");
    metadata.put("source", "Synthetic manual");
    metadata.put("language", "en-US");
    metadata.put("tags", List.of("tag"));
    metadata.put("product_models", List.of("CF-100"));
    metadata.put("valid_from", "2020-01-01T08:00:00+08:00");
    metadata.put("valid_until", "2021-01-01T08:00:00+08:00");
    metadata.put("revision", 0);
    mvc.perform(
            put("/api/v1/documents/" + document + "/metadata")
                .header("Authorization", token)
                .contentType("application/json")
                .content(json.writeValueAsString(metadata)))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/documents/" + document + "/metadata").header("Authorization", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.valid_from").value("2020-01-01T00:00:00Z"))
        .andExpect(jsonPath("$.product_models[0]").value("CF-100"));
    assertThat(retrieval.scope(actor, q).versions()).doesNotContain(version);
    assertThatThrownBy(
            () ->
                retrieval.checkEvidence(
                    actor, evidence, new RetrievalService.Scope(List.of(version), false, "", -1)))
        .isInstanceOf(ApiException.class);
    mvc.perform(get("/api/v1/answers").header("Authorization", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == '" + answer + "')]").isEmpty());
    mvc.perform(
            get("/api/v1/documents/" + document + "/metadata-history")
                .header("Authorization", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].revision").value(1));
    mvc.perform(
            put("/api/v1/documents/" + document + "/metadata")
                .header("Authorization", token)
                .contentType("application/json")
                .content(json.writeValueAsString(metadata)))
        .andExpect(status().isConflict());
    metadata.put("revision", 1);
    metadata.put("valid_until", "2999-01-01T00:00:00Z");
    mvc.perform(
            put("/api/v1/documents/" + document + "/metadata")
                .header("Authorization", token)
                .contentType("application/json")
                .content(json.writeValueAsString(metadata)))
        .andExpect(status().isOk());
    assertThat(retrieval.scope(actor, q).versions()).contains(version);
    assertThat(
            db.list(
                "SELECT id FROM document_metadata_history WHERE tenant_id=? AND document_id=?",
                tenant,
                document))
        .hasSize(2);
  }

  @Test
  void metadataRejectsMalformedDatesAndReversedValidityWithoutMutation() throws Exception {
    for (String until : List.of("2019-01-01T00:00:00Z", "2020-01-01T00:00:00", "not-a-date")) {
      String body =
          json.writeValueAsString(
              Map.of(
                  "title",
                  "Invalid",
                  "source",
                  "",
                  "language",
                  "zh",
                  "tags",
                  List.of(),
                  "product_models",
                  List.of(),
                  "valid_from",
                  "2020-01-01T00:00:00Z",
                  "valid_until",
                  until,
                  "revision",
                  0));
      mvc.perform(
              put("/api/v1/documents/" + document + "/metadata")
                  .header("Authorization", token)
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isBadRequest());
    }
    assertThat(Db.num(auth.document(actor, document, "read"), "revision")).isZero();
    assertThat(db.list("SELECT id FROM document_metadata_history WHERE document_id=?", document))
        .isEmpty();
  }

  @Test
  void knowledgeAttributesTransferAndConflict() throws Exception {
    String next = Db.id();
    db.exec(
        "INSERT INTO members(id,tenant_id,name,role) VALUES(?,?,?,'KNOWLEDGE_MANAGER')",
        next,
        tenant,
        "Next");
    String nextToken = "Bearer " + auth.credential(tenant, next, "MEMBER", null);
    String body =
        json.writeValueAsString(
            Map.of(
                "name",
                "Updated",
                "description",
                "Scope",
                "language",
                "en-US",
                "tags",
                List.of("manual", "manual"),
                "owner_id",
                next,
                "revision",
                0));
    mvc.perform(
            put("/api/v1/knowledge-bases/" + kb)
                .header("Authorization", token)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revision").value(1));
    mvc.perform(get("/api/v1/knowledge-bases/" + kb).header("Authorization", token))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/knowledge-bases/" + kb).header("Authorization", nextToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Updated"))
        .andExpect(jsonPath("$.language").value("en-US"))
        .andExpect(jsonPath("$.tags.length()").value(1));
    mvc.perform(
            put("/api/v1/knowledge-bases/" + kb)
                .header("Authorization", nextToken)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isConflict());
    assertThat(
            db.list(
                "SELECT id FROM audit_events WHERE tenant_id=? AND action='KB_ATTRIBUTES_UPDATE'",
                tenant))
        .hasSize(1);
  }

  @Test
  void knowledgeAttributesRejectInvalidOwnersAndTagsAtomically() throws Exception {
    String other = Db.id();
    for (String owner : List.of(other, member)) {
      var body =
          Map.of(
              "name",
              "Wrong",
              "description",
              "",
              "language",
              "zh",
              "tags",
              owner.equals(member) ? List.of("") : List.of("tag"),
              "owner_id",
              owner,
              "revision",
              0);
      mvc.perform(
              put("/api/v1/knowledge-bases/" + kb)
                  .header("Authorization", token)
                  .contentType("application/json")
                  .content(json.writeValueAsString(body)))
          .andExpect(status().isBadRequest());
    }
    assertThat(Db.str(auth.kb(actor, kb, "read"), "name")).isEqualTo("Knowledge");
    assertThat(Db.num(auth.kb(actor, kb, "read"), "revision")).isZero();
  }

  @Test
  void overviewFiltersRestrictedDocumentsAndDeduplicatesSourceObjects() throws Exception {
    db.exec("UPDATE document_versions SET size_bytes=100 WHERE id=?", version);
    String draft = Db.id();
    db.exec(
        "INSERT INTO document_versions(id,tenant_id,document_id,object_key,filename,digest,state,size_bytes) VALUES(?,?,?,'key','manual.txt','digest','PARSED',100)",
        draft,
        tenant,
        document);
    String hidden = Db.id(), hiddenVersion = Db.id();
    db.exec(
        "INSERT INTO documents(id,tenant_id,kb_id,title,restricted,published_version) VALUES(?,?,?,'Restricted',TRUE,?)",
        hidden,
        tenant,
        kb,
        hiddenVersion);
    db.exec(
        "INSERT INTO document_versions(id,tenant_id,document_id,object_key,filename,digest,state,ever_published,size_bytes) VALUES(?,?,?,'hidden','hidden.txt','hidden','READY',TRUE,999)",
        hiddenVersion,
        tenant,
        hidden);
    db.exec(
        "INSERT INTO jobs(id,tenant_id,version_id,kind,state,request_key) VALUES(?,?,?,'PARSE','FAILED',?)",
        Db.id(),
        tenant,
        version,
        Db.id());
    db.exec(
        "INSERT INTO jobs(id,tenant_id,version_id,kind,state,request_key) VALUES(?,?,?,'PARSE','FAILED',?)",
        Db.id(),
        tenant,
        hiddenVersion,
        Db.id());
    String app = Db.id();
    db.exec(
        "INSERT INTO applications(id,tenant_id,name,description,published) VALUES(?,?,?,'',TRUE)",
        app,
        tenant,
        "Reference");
    db.exec(
        "INSERT INTO application_bindings(tenant_id,application_id,kb_id) VALUES(?,?,?)",
        tenant,
        app,
        kb);
    mvc.perform(get("/api/v1/knowledge-bases/" + kb + "/overview").header("Authorization", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.document_count").value(1))
        .andExpect(jsonPath("$.effective_chunk_count").value(1))
        .andExpect(jsonPath("$.failed_job_count").value(1))
        .andExpect(jsonPath("$.known_source_bytes").value(100))
        .andExpect(jsonPath("$.unknown_source_objects").value(0))
        .andExpect(jsonPath("$.applications[0].name").value("Reference"));
    String reader = Db.id();
    db.exec(
        "INSERT INTO members(id,tenant_id,name,role) VALUES(?,?,'Reader','USER')", reader, tenant);
    db.exec(
        "INSERT INTO permissions(tenant_id,resource_id,subject_id,action) VALUES(?,?,?,'read')",
        tenant,
        kb,
        reader);
    String readerToken = "Bearer " + auth.credential(tenant, reader, "MEMBER", null);
    mvc.perform(
            get("/api/v1/knowledge-bases/" + kb + "/overview").header("Authorization", readerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.document_count").value(1))
        .andExpect(jsonPath("$.failed_job_count").doesNotExist())
        .andExpect(jsonPath("$.applications_visible").value(false))
        .andExpect(jsonPath("$.applications").isEmpty());
    mvc.perform(
            get("/api/v1/knowledge-bases/" + kb + "/settings").header("Authorization", readerToken))
        .andExpect(status().isNotFound());
    db.exec("DELETE FROM permissions WHERE tenant_id=? AND subject_id=?", tenant, reader);
    mvc.perform(
            get("/api/v1/knowledge-bases/" + kb + "/overview").header("Authorization", readerToken))
        .andExpect(status().isNotFound());
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
            "/knowledge-bases/" + kb + "/impact",
            "/documents/" + document + "/versions",
            "/documents/" + document + "/metadata",
            "/documents/" + document + "/metadata-history",
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
                .content(
                    json.writeValueAsString(
                        Map.of("version_id", version, "revision", 5, "version_revision", 0))))
        .andExpect(status().isConflict());
    db.exec("UPDATE document_versions SET state='PARSED' WHERE id=?", version);
    mvc.perform(
            post("/api/v1/documents/" + document + "/publications")
                .header("Authorization", token)
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of("version_id", version, "revision", 0, "version_revision", 0))))
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
    bindIndexJob(job);
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
        .thenReturn(
            Map.of(
                "dense",
                List.of(),
                "bm25",
                List.of(),
                "fused",
                List.of(Map.of("id", chunk, "score", 1)),
                "degraded",
                false));
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
        .andExpect(jsonPath("$.excluded[0].reason").value("BELOW_MINIMUM_SCORE"))
        .andExpect(jsonPath("$.evidence_status").value("BELOW_THRESHOLD"))
        .andExpect(jsonPath("$.query_processing.original").value("test"))
        .andExpect(jsonPath("$.query_processing.rewritten").value("test"))
        .andExpect(jsonPath("$.timings_ms.recall").isNumber())
        .andExpect(jsonPath("$.model_usage.rerank.state").value("UNKNOWN"));
    var query = new Query("test", null, List.of(), "hybrid", 6, false, null);
    assertThat(
            (List<?>)
                retrieval
                    .search(actor, query, retrieval.scope(actor, query), token)
                    .get("evidence"))
        .hasSize(1);
    assertThat(retrieval.search(actor, query, retrieval.scope(actor, query), token))
        .doesNotContainKeys("query_processing", "timings_ms", "model_usage", "recall", "excluded");
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
        .thenReturn(
            Map.of(
                "dense",
                List.of(),
                "bm25",
                List.of(),
                "fused",
                List.of(Map.of("id", chunk, "score", 1)),
                "degraded",
                false));
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

  private Map<String, Object> modelInput(String key) {
    Map<String, Object> input = new HashMap<>();
    input.put("name", "DeepSeek test");
    input.put("kind", "GENERATION");
    input.put("base_url", "https://api.deepseek.com");
    input.put("model", "deepseek-v4-flash");
    input.put("model_revision", "");
    input.put("external_processing", true);
    input.put("api_key", key);
    input.put("revision", 0);
    return input;
  }

  @Test
  void modelProfilesEncryptKeysHideThemAndAuditChanges() throws Exception {
    var result =
        mvc.perform(
                post("/api/v1/model-profiles")
                    .header("Authorization", token)
                    .contentType("application/json")
                    .content(json.writeValueAsString(modelInput("test-profile-secret"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.key_configured").value(true))
            .andReturn();
    String body = result.getResponse().getContentAsString();
    assertThat(body).doesNotContain("test-profile-secret", "encrypted_key", "api_key");
    String id = json.readTree(body).get("id").asText();
    String encrypted =
        Db.str(db.one("SELECT * FROM model_profiles WHERE id=?", id), "encrypted_key");
    assertThat(encrypted).startsWith("v1:").doesNotContain("test-profile-secret");
    mvc.perform(get("/api/v1/model-profiles").header("Authorization", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].key_configured").value(true));
    assertThat(
            db.list(
                "SELECT * FROM audit_events WHERE tenant_id=? AND action='MODEL_PROFILE_CREATE'",
                tenant))
        .hasSize(1);
    mvc.perform(
            put("/api/v1/model-profiles/" + id)
                .header("Authorization", token)
                .contentType("application/json")
                .content(json.writeValueAsString(modelInput(null))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revision").value(1));
    assertThat(Db.str(db.one("SELECT * FROM model_profiles WHERE id=?", id), "encrypted_key"))
        .isEqualTo(encrypted);
    mvc.perform(
            put("/api/v1/model-profiles/" + id)
                .header("Authorization", token)
                .contentType("application/json")
                .content(json.writeValueAsString(modelInput("new-test-secret"))))
        .andExpect(status().isConflict());
  }

  @Test
  void modelProfilesRejectUnapprovedEndpointsAndInvalidMetadata() throws Exception {
    for (String base :
        List.of(
            "http://127.0.0.1:8090",
            "https://api.deepseek.com.evil.test",
            "https://api.deepseek.com/v1/../internal",
            "https://user:password@api.deepseek.com",
            "https://api.deepseek.com?target=internal")) {
      var input = modelInput(null);
      input.put("base_url", base);
      mvc.perform(
              post("/api/v1/model-profiles")
                  .header("Authorization", token)
                  .contentType("application/json")
                  .content(json.writeValueAsString(input)))
          .andExpect(status().isBadRequest());
    }
    var input = modelInput(null);
    input.put("external_processing", false);
    mvc.perform(
            post("/api/v1/model-profiles")
                .header("Authorization", token)
                .contentType("application/json")
                .content(json.writeValueAsString(input)))
        .andExpect(status().isBadRequest());
    input = modelInput(null);
    input.put("kind", "EMBEDDING");
    mvc.perform(
            post("/api/v1/model-profiles")
                .header("Authorization", token)
                .contentType("application/json")
                .content(json.writeValueAsString(input)))
        .andExpect(status().isBadRequest());
    assertThat(db.list("SELECT * FROM model_profiles WHERE tenant_id=?", tenant)).isEmpty();
  }

  @Test
  void modelProfilesAreAdminOnlyAndTenantScoped() throws Exception {
    String other = Db.id();
    db.exec("INSERT INTO tenants(id,name) VALUES(?,?)", other, "other");
    String hidden = Db.id();
    db.exec(
        "INSERT INTO model_profiles(id,tenant_id,name,kind,base_url,model,model_revision,external_processing,encrypted_key) VALUES(?,?,?,'GENERATION','https://api.deepseek.com','model','',TRUE,'')",
        hidden,
        other,
        "hidden");
    mvc.perform(get("/api/v1/model-profiles").header("Authorization", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
    mvc.perform(
            put("/api/v1/model-profiles/" + hidden)
                .header("Authorization", token)
                .contentType("application/json")
                .content(json.writeValueAsString(modelInput(null))))
        .andExpect(status().isNotFound());
    db.exec("UPDATE members SET role='USER' WHERE id=?", member);
    mvc.perform(get("/api/v1/model-profiles").header("Authorization", token))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/model-profiles/policy").header("Authorization", token))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/v1/model-profiles")
                .header("Authorization", token)
                .contentType("application/json")
                .content(json.writeValueAsString(modelInput("secret"))))
        .andExpect(status().isNotFound());
  }

  @Test
  void uploadKeyRejectsChangedPayloadAndDuplicateContentIsExplicit() throws Exception {
    String key = Db.id();
    var file = new MockMultipartFile("file", "guide.txt", "text/plain", "upload test".getBytes());
    mvc.perform(
            multipart("/api/v1/knowledge-bases/" + kb + "/documents")
                .file(file)
                .header("Authorization", token)
                .header("Idempotency-Key", key))
        .andExpect(status().isOk());
    mvc.perform(
            multipart("/api/v1/knowledge-bases/" + kb + "/documents")
                .file(
                    new MockMultipartFile("file", "guide.txt", "text/plain", "changed".getBytes()))
                .header("Authorization", token)
                .header("Idempotency-Key", key))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    mvc.perform(
            multipart("/api/v1/knowledge-bases/" + kb + "/documents")
                .file(file)
                .header("Authorization", token)
                .header("Idempotency-Key", Db.id()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_FILE"));
    assertThat(db.list("SELECT * FROM jobs WHERE tenant_id=?", tenant)).hasSize(1);
  }

  @Test
  void enterpriseProvisioningCreatesSeparateTenantsAndDoesNotReplayCredentials() throws Exception {
    String operator = "bootstrap-testing-secret-at-least-32-chars", key = Db.id();
    String body =
        json.writeValueAsString(Map.of("name", "new enterprise", "owner_name", "new owner"));
    mvc.perform(
            post("/api/v1/enterprises")
                .header("X-Bootstrap-Token", "wrong")
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isUnauthorized());
    var first =
        mvc.perform(
                post("/api/v1/enterprises")
                    .header("X-Bootstrap-Token", operator)
                    .header("Idempotency-Key", key)
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isOk())
            .andReturn();
    var one = json.readTree(first.getResponse().getContentAsString());
    var second =
        mvc.perform(
                post("/api/v1/enterprises")
                    .header("X-Bootstrap-Token", operator)
                    .header("Idempotency-Key", Db.id())
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isOk())
            .andReturn();
    var two = json.readTree(second.getResponse().getContentAsString());
    assertThat(one.get("tenant_id").asText()).isNotEqualTo(two.get("tenant_id").asText());
    mvc.perform(
            get("/api/v1/knowledge-bases/" + kb)
                .header("Authorization", "Bearer " + one.get("token").asText()))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/v1/enterprises")
                .header("X-Bootstrap-Token", operator)
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isConflict());
  }

  @Test
  void memberLifecycleRevokesCredentialsAndDoesNotReviveThem() throws Exception {
    String id = Db.id();
    db.exec(
        "INSERT INTO members(id,tenant_id,name,role) VALUES(?,?,?,'USER')", id, tenant, "member");
    String old = "Bearer " + auth.credential(tenant, id, "MEMBER", null);
    for (int revision = 0; revision < 2; revision++) {
      mvc.perform(
              put("/api/v1/members/" + id)
                  .header("Authorization", token)
                  .contentType("application/json")
                  .content(
                      json.writeValueAsString(
                          Map.of(
                              "name",
                              "renamed",
                              "role",
                              "DEVELOPER",
                              "state",
                              revision == 0 ? "DISABLED" : "ACTIVE",
                              "revision",
                              revision))))
          .andExpect(status().isOk());
      mvc.perform(get("/api/v1/me").header("Authorization", old))
          .andExpect(status().isUnauthorized());
    }
    var issued =
        mvc.perform(post("/api/v1/members/" + id + "/credentials").header("Authorization", token))
            .andExpect(status().isOk())
            .andReturn();
    String fresh =
        "Bearer " + json.readTree(issued.getResponse().getContentAsString()).get("token").asText();
    mvc.perform(get("/api/v1/me").header("Authorization", fresh))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("DEVELOPER"));
    mvc.perform(
            put("/api/v1/members/" + id)
                .header("Authorization", token)
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "name",
                            "renamed",
                            "role",
                            "DEVELOPER",
                            "state",
                            "REMOVED",
                            "revision",
                            2))))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/me").header("Authorization", fresh))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            put("/api/v1/members/" + id)
                .header("Authorization", token)
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "name",
                            "renamed",
                            "role",
                            "DEVELOPER",
                            "state",
                            "ACTIVE",
                            "revision",
                            3))))
        .andExpect(status().isConflict());
  }

  @Test
  void adminCannotObtainOwnerCredentialAndOwnerCannotBeRemoved() throws Exception {
    String admin = Db.id();
    db.exec(
        "INSERT INTO members(id,tenant_id,name,role) VALUES(?,?,?,'ADMIN')",
        admin,
        tenant,
        "admin");
    String credential = "Bearer " + auth.credential(tenant, admin, "MEMBER", null);
    mvc.perform(
            post("/api/v1/members/" + member + "/credentials").header("Authorization", credential))
        .andExpect(status().isNotFound());
    mvc.perform(
            put("/api/v1/members/" + member)
                .header("Authorization", token)
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "name", "owner", "role", "OWNER", "state", "REMOVED", "revision", 0))))
        .andExpect(status().isConflict());
    mvc.perform(
            put("/api/v1/members/" + admin)
                .header("Authorization", token)
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "name", "admin", "role", "OWNER", "state", "ACTIVE", "revision", 0))))
        .andExpect(status().isConflict());
  }

  @Test
  void multiSubjectAuthorizationSnapshotAndRevisionConflict() throws Exception {
    String reader = Db.id(), editor = Db.id();
    for (String id : List.of(reader, editor))
      db.exec(
          "INSERT INTO members(id,tenant_id,name,role) VALUES(?,?,?,'KNOWLEDGE_MANAGER')",
          id,
          tenant,
          "test");
    String body =
        json.writeValueAsString(
            Map.of(
                "revision",
                0,
                "grants",
                Map.of(reader, List.of("read"), editor, List.of("read", "edit"))));
    mvc.perform(
            put("/api/v1/knowledge-bases/" + kb + "/permissions")
                .header("Authorization", token)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk());
    mvc.perform(
            get("/api/v1/knowledge-bases/" + kb + "/authorization").header("Authorization", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revision").value(1))
        .andExpect(jsonPath("$.grants.length()").value(3));
    mvc.perform(
            put("/api/v1/knowledge-bases/" + kb + "/permissions")
                .header("Authorization", token)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isConflict());
    assertThat(db.list("SELECT * FROM permissions WHERE tenant_id=? AND resource_id=?", tenant, kb))
        .hasSize(3);
  }

  @Test
  void documentAuthorizationCannotExpandKnowledgeBasePermissions() throws Exception {
    String reader = Db.id();
    db.exec(
        "INSERT INTO members(id,tenant_id,name,role) VALUES(?,?,?,'USER')",
        reader,
        tenant,
        "reader");
    db.exec(
        "INSERT INTO permissions(tenant_id,resource_id,subject_id,action) VALUES(?,?,?,'read')",
        tenant,
        kb,
        reader);
    mvc.perform(
            put("/api/v1/documents/" + document + "/permissions")
                .header("Authorization", token)
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "revision", 0, "grants", Map.of(reader, List.of("read", "download"))))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("DOCUMENT_PERMISSION_EXCEEDS_KB"));
    assertThat(Db.bool(db.one("SELECT * FROM documents WHERE id=?", document), "restricted"))
        .isFalse();
    mvc.perform(
            put("/api/v1/documents/" + document + "/permissions")
                .header("Authorization", token)
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "revision",
                            0,
                            "grants",
                            Map.of(reader, List.of("read"), member, List.of("manage"))))))
        .andExpect(status().isOk());
    mvc.perform(
            get("/api/v1/documents/" + document + "/authorization").header("Authorization", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.restricted").value(true));
  }

  @Test
  void invalidOrRemovedAclSubjectRollsBackWholeReplacement() throws Exception {
    String removed = Db.id();
    db.exec(
        "INSERT INTO members(id,tenant_id,name,role,active,removed) VALUES(?,?,?,'USER',FALSE,TRUE)",
        removed,
        tenant,
        "removed");
    for (String subject : List.of(removed, Db.id()))
      mvc.perform(
              put("/api/v1/knowledge-bases/" + kb + "/permissions")
                  .header("Authorization", token)
                  .contentType("application/json")
                  .content(
                      json.writeValueAsString(
                          Map.of("revision", 0, "grants", Map.of(subject, List.of("read"))))))
          .andExpect(status().isNotFound());
    assertThat(Db.num(db.one("SELECT * FROM knowledge_bases WHERE id=?", kb), "revision")).isZero();
    assertThat(db.list("SELECT * FROM permissions WHERE tenant_id=? AND resource_id=?", tenant, kb))
        .isEmpty();
  }

  @Test
  void scopedApplicationKeyCannotGenerateOrReadMetadata() throws Exception {
    String app = Db.id();
    db.exec(
        "INSERT INTO applications(id,tenant_id,name,description,published) VALUES(?,?,?,'',TRUE)",
        app,
        tenant,
        "scope-test");
    var issued =
        mvc.perform(
                post("/api/v1/applications/" + app + "/credentials")
                    .header("Authorization", token)
                    .contentType("application/json")
                    .content(
                        json.writeValueAsString(
                            Map.of("scopes", List.of("SEARCH"), "expires_in_days", 2))))
            .andExpect(status().isOk())
            .andReturn();
    var payload = json.readTree(issued.getResponse().getContentAsString());
    String appToken = "Bearer " + payload.get("token").asText();
    mvc.perform(get("/api/v1/knowledge-bases").header("Authorization", appToken))
        .andExpect(status().isNotFound());
    String query = json.writeValueAsString(Map.of("query", "test", "mode", "hybrid", "limit", 6));
    mvc.perform(
            post("/api/v1/answers")
                .header("Authorization", appToken)
                .header("Idempotency-Key", Db.id())
                .contentType("application/json")
                .content(query))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/v1/retrieval/search")
                .header("Authorization", appToken)
                .header("Idempotency-Key", Db.id())
                .contentType("application/json")
                .content(query))
        .andExpect(status().isOk());
    mvc.perform(
            delete("/api/v1/applications/" + app + "/credentials/" + payload.get("id").asText())
                .header("Authorization", token))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/v1/retrieval/search")
                .header("Authorization", appToken)
                .header("Idempotency-Key", Db.id())
                .contentType("application/json")
                .content(query))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void invalidScopesExpiryAndCrossApplicationRevocationAreRejected() throws Exception {
    String app = Db.id(), other = Db.id();
    for (String id : List.of(app, other))
      db.exec(
          "INSERT INTO applications(id,tenant_id,name,description,published) VALUES(?,?,?,'',TRUE)",
          id,
          tenant,
          "test");
    for (Map<String, Object> input :
        List.<Map<String, Object>>of(
            Map.of("scopes", List.of("ADMIN"), "expires_in_days", 90),
            Map.of("scopes", List.of("SEARCH"), "expires_in_days", 0),
            Map.of("scopes", List.of(), "expires_in_days", 90)))
      mvc.perform(
              post("/api/v1/applications/" + app + "/credentials")
                  .header("Authorization", token)
                  .contentType("application/json")
                  .content(json.writeValueAsString(input)))
          .andExpect(status().isBadRequest());
    var issued =
        mvc.perform(
                post("/api/v1/applications/" + app + "/credentials").header("Authorization", token))
            .andExpect(status().isOk())
            .andReturn();
    var key = json.readTree(issued.getResponse().getContentAsString());
    mvc.perform(
            delete("/api/v1/applications/" + other + "/credentials/" + key.get("id").asText())
                .header("Authorization", token))
        .andExpect(status().isNotFound());
    db.exec(
        "UPDATE credentials SET expires_at=TIMESTAMP '2000-01-01 00:00:00' WHERE id=?",
        key.get("id").asText());
    mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + key.get("token").asText()))
        .andExpect(status().isUnauthorized());
  }

  void bindIndexJob(String job) {
    var ids = new ArrayList<String>();
    for (String kind : List.of("EMBEDDING", "RERANK", "GENERATION"))
      ids.add(
          modelProfiles
              .save(
                  actor,
                  null,
                  new ModelProfileService.Input(
                      "Fixture",
                      kind,
                      "https://api.deepseek.com",
                      "fixture",
                      kind.equals("EMBEDDING") ? "revision" : "",
                      kind.equals("EMBEDDING") ? 512 : null,
                      true,
                      "",
                      0))
              .id());
    String config =
        configurations
            .create(
                actor,
                kb,
                new KnowledgeConfiguration.Definition(
                    "fixture",
                    new KnowledgeConfiguration.Parsing(20),
                    new KnowledgeConfiguration.Chunking(120, 200, 10),
                    new KnowledgeConfiguration.Retrieval("hybrid", 4, .2, false),
                    new KnowledgeConfiguration.Models(ids.get(0), ids.get(1), ids.get(2))))
            .id();
    db.exec("UPDATE jobs SET configuration_id=? WHERE id=?", config, job);
    db.exec("UPDATE document_versions SET configuration_id=? WHERE id=?", config, version);
  }
}
