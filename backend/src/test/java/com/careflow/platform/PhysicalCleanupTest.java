package com.careflow.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

class PhysicalCleanupTest extends ContentTestSupport {
  @Autowired CleanupRepository repository;
  @Autowired PhysicalCleanupService cleanup;
  @Autowired DocumentPublicationService publications;
  @Autowired IndexCacheReferences references;

  @BeforeEach
  void storeResponses() {
    when(worker.call(startsWith("/internal/v1/index/purge-"), any()))
        .thenReturn(Map.of("verified", true, "compactions", List.of()));
    when(worker.call(eq("/internal/v1/index/compactions"), any()))
        .thenReturn(Map.of("complete", true));
  }

  String document() {
    return Db.str(
        db.one("SELECT document_id FROM document_versions WHERE id=?", version), "document_id");
  }

  void due(String request) {
    db.exec(
        "UPDATE cleanup_requests SET not_before=? WHERE id=?",
        Timestamp.from(Instant.now().minusSeconds(1)),
        request);
  }

  void drain() {
    for (int i = 0; i < 30; i++) {
      var pending =
          db.list(
              "SELECT id FROM cleanup_requests WHERE tenant_id=? AND state IN ('PENDING','RETRY')",
              tenant);
      if (pending.isEmpty()) return;
      for (var request : pending) {
        due(Db.str(request, "id"));
        cleanup.processOne(Db.str(request, "id"));
      }
    }
    throw new AssertionError("Cleanup failed to converge");
  }

  @Test
  void deletionPurgesVersionAndHistoriesButRetainsTombstoneAndAudit() {
    String id = chunk(0, "private fixture", 2, true, "{}");
    db.exec(
        "INSERT INTO chunk_revisions(id,tenant_id,chunk_id,revision,previous_content,actor_id,reason) VALUES(?,?,?,0,'older private text',?,'review')",
        Db.id(),
        tenant,
        id,
        actor.subject());
    String answer = Db.id();
    db.exec(
        "INSERT INTO answers(id,tenant_id,subject_id,question,content) VALUES(?,?,?,'question','derived private text')",
        answer,
        tenant,
        actor.subject());
    db.exec(
        "INSERT INTO answer_evidence(answer_id,document_id,version_id,chunk_id) VALUES(?,?,?,?)",
        answer,
        document(),
        version,
        id);
    publications.delete(actor, document(), 0);
    assertThatThrownBy(() -> auth.version(actor, version, "read")).isInstanceOf(ApiException.class);
    drain();
    verify(blobs).purge("fixture");
    assertThat(db.list("SELECT * FROM chunks WHERE tenant_id=?", tenant)).isEmpty();
    assertThat(db.list("SELECT * FROM chunk_revisions WHERE tenant_id=?", tenant)).isEmpty();
    assertThat(db.list("SELECT * FROM answers WHERE tenant_id=?", tenant)).isEmpty();
    var tombstone = db.one("SELECT * FROM document_versions WHERE id=?", version);
    assertThat(Db.str(tombstone, "state")).isEqualTo("PURGED");
    assertThat(Db.str(tombstone, "object_key")).isEmpty();
    assertThat(Db.str(db.one("SELECT * FROM documents WHERE id=?", document()), "status"))
        .isEqualTo("DELETED");
    assertThat(
            db.list(
                "SELECT * FROM audit_events WHERE tenant_id=? AND action='PHYSICAL_CLEANUP_COMPLETE'",
                tenant))
        .hasSize(2);
  }

  @Test
  void storageFailureRetainsContentAndRetriesFromTheDurablePhase() {
    chunk(0, "private fixture", 2, true, "{}");
    publications.delete(actor, document(), 0);
    doThrow(new ApiException(503, "STORAGE_PURGE_UNAVAILABLE", "fixture failure"))
        .when(blobs)
        .purge(anyString());
    boolean failed = false;
    for (int i = 0; i < 20 && !failed; i++) {
      for (var request :
          db.list(
              "SELECT id FROM cleanup_requests WHERE tenant_id=? AND state='PENDING'", tenant)) {
        due(Db.str(request, "id"));
        cleanup.processOne(Db.str(request, "id"));
      }
      failed =
          !db.list("SELECT id FROM cleanup_requests WHERE tenant_id=? AND state='RETRY'", tenant)
              .isEmpty();
    }
    assertThat(failed).isTrue();
    assertThat(db.list("SELECT * FROM chunks WHERE version_id=?", version)).hasSize(1);
    doNothing().when(blobs).purge(anyString());
    drain();
    assertThat(
            db.list("SELECT * FROM cleanup_requests WHERE tenant_id=? AND state<>'DONE'", tenant))
        .isEmpty();
  }

  @Test
  void sharedObjectAndSharedCacheRemainForAnotherLiveDocument() {
    chunk(0, "shared fixture", 2, true, "{}");
    String model = "a".repeat(64), gen = Db.id();
    references.register(tenant, version, model, gen);
    String otherDoc = Db.id(), otherVersion = Db.id();
    String kb = Db.str(db.one("SELECT kb_id FROM documents WHERE id=?", document()), "kb_id");
    db.exec(
        "INSERT INTO documents(id,tenant_id,kb_id,title) VALUES(?,?,?,'live')",
        otherDoc,
        tenant,
        kb);
    db.exec(
        "INSERT INTO document_versions(id,tenant_id,document_id,object_key,filename,digest,state) VALUES(?,?,?,'fixture','live.txt','digest','READY')",
        otherVersion,
        tenant,
        otherDoc);
    db.exec(
        "INSERT INTO index_cache_references(tenant_id,version_id,generation_id,model_identity,content_hash) VALUES(?,?,?,?,?)",
        tenant,
        otherVersion,
        Db.id(),
        model,
        IndexManifest.hash("shared fixture"));
    publications.delete(actor, document(), 0);
    drain();
    verify(blobs, never()).purge(anyString());
    var capture = ArgumentCaptor.forClass(WorkerProtocolV1.CachePurge.class);
    verify(worker).call(eq("/internal/v1/index/purge-cache"), capture.capture());
    assertThat(capture.getValue().entries()).isEmpty();
    assertThat(db.list("SELECT * FROM index_cache_references WHERE tenant_id=?", tenant))
        .hasSize(1);
    assertThat(
            Db.str(
                db.one("SELECT * FROM document_versions WHERE id=?", otherVersion), "object_key"))
        .isEqualTo("fixture");
  }

  @Test
  void activeResourceAndExpiredCleanupLeaseCannotPurgeContent() {
    chunk(0, "protected", 2, true, "{}");
    String request = repository.enqueue(tenant, "DOCUMENT_VERSION", version, actor.subject());
    due(request);
    cleanup.processOne(request);
    assertThat(Db.str(db.one("SELECT * FROM cleanup_requests WHERE id=?", request), "state"))
        .isEqualTo("BLOCKED");
    verifyNoInteractions(blobs);
    verify(worker, never()).call(startsWith("/internal/v1/index/purge-"), any());
    db.exec("UPDATE documents SET status='DELETED' WHERE id=?", document());
    db.exec("UPDATE cleanup_requests SET state='PENDING' WHERE id=?", request);
    due(request);
    var claim = repository.claim(request);
    db.exec(
        "UPDATE cleanup_requests SET lease_until=? WHERE id=?",
        Timestamp.from(Instant.now().minusSeconds(1)),
        request);
    assertThatThrownBy(
            () ->
                repository.complete(
                    claim, () -> db.exec("DELETE FROM chunks WHERE version_id=?", version)))
        .isInstanceOf(ApiException.class);
    assertThat(db.list("SELECT * FROM chunks WHERE version_id=?", version)).hasSize(1);
  }

  @Test
  void knowledgeBaseCleanupWaitsForEveryVersionAndRemovesConfigurationContent() {
    chunk(0, "fixture", 2, true, "{}");
    String kb = Db.str(db.one("SELECT kb_id FROM documents WHERE id=?", document()), "kb_id");
    db.exec("UPDATE knowledge_bases SET status='DELETED' WHERE id=?", kb);
    String request = repository.enqueue(tenant, "KNOWLEDGE_BASE", kb, actor.subject());
    due(request);
    cleanup.processOne(request);
    assertThat(db.one("SELECT purged_at FROM knowledge_bases WHERE id=?", kb).get("purged_at"))
        .isNull();
    drain();
    assertThat(db.one("SELECT purged_at FROM knowledge_bases WHERE id=?", kb).get("purged_at"))
        .isNotNull();
    assertThat(Db.str(db.one("SELECT name FROM knowledge_bases WHERE id=?", kb), "name")).isEmpty();
    assertThat(db.list("SELECT id FROM chunks WHERE tenant_id=?", tenant)).isEmpty();
  }

  @Test
  void retiredGenerationCleanupPreservesCurrentContentAndLatestCacheReference() {
    chunk(0, "fixture", 2, true, "{}");
    String retired = Db.id(), active = Db.id(), model = "a".repeat(64);
    db.exec(
        "INSERT INTO index_generations(id,tenant_id,version_id,job_id,lease_token,configuration_id,model_identity,content_revision,state,completed_at) VALUES(?,?,?,?,?,?,?,0,'RETIRED',?)",
        retired,
        tenant,
        version,
        Db.id(),
        Db.id(),
        Db.id(),
        model,
        Timestamp.from(Instant.now().minusSeconds(90000)));
    db.exec("UPDATE document_versions SET active_index_generation=? WHERE id=?", active, version);
    references.register(tenant, version, model, retired);
    references.register(tenant, version, model, active);
    repository.enqueue(tenant, "INDEX_GENERATION", retired, actor.subject());
    drain();
    verify(blobs, never()).purge(anyString());
    assertThat(db.list("SELECT id FROM chunks WHERE version_id=?", version)).hasSize(1);
    assertThat(
            Db.str(
                db.one(
                    "SELECT generation_id FROM index_cache_references WHERE version_id=?", version),
                "generation_id"))
        .isEqualTo(active);
    assertThat(Db.str(db.one("SELECT state FROM index_generations WHERE id=?", retired), "state"))
        .isEqualTo("PURGED");
  }

  @Test
  void publicCleanupListHidesInternalPayloadAndOtherTenantCannotRetry() throws Exception {
    String request = repository.enqueue(tenant, "DOCUMENT_VERSION", version, actor.subject());
    db.exec(
        "UPDATE cleanup_requests SET state='RETRY',payload_json='sensitive internal payload',lease_token='secret lease' WHERE id=?",
        request);
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/cleanup-requests")
                .header("Authorization", token))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("sensitive internal payload"))))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret lease"))));
    setup();
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/cleanup-requests/" + request + "/retry")
                .header("Authorization", token)
                .contentType("application/json")
                .content("{\"reason\":\"retry\"}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isNotFound());
  }
}
