package com.careflow.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.careflow.platform.Identity.Actor;
import com.careflow.platform.KnowledgeConfiguration.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
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
class KnowledgeConfigurationTest {
  @Autowired Db db;
  @Autowired Identity auth;
  @Autowired ModelProfileService profiles;
  @Autowired KnowledgeConfigurationService configurations;
  @Autowired ModelKeyVault vault;
  @Autowired DocumentUploadService uploads;
  @Autowired DocumentDraftService drafts;
  @Autowired Tasks tasks;
  @Autowired IndexMaintenanceService indexes;
  @Autowired RetrievalService retrieval;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @MockitoBean BlobStore blobs;
  @MockitoBean WorkerClient worker;
  Actor actor;
  String tenant, member, token, kb, embedding, rerank, generation;

  @BeforeEach
  void setup() {
    tenant = Db.id();
    member = Db.id();
    kb = Db.id();
    db.exec("INSERT INTO tenants(id,name) VALUES(?,'configuration fixture')", tenant);
    db.exec(
        "INSERT INTO members(id,tenant_id,name,role) VALUES(?,?,'Owner','OWNER')", member, tenant);
    token = "Bearer " + auth.credential(tenant, member, "MEMBER", null);
    actor = auth.authenticate(token);
    db.exec(
        "INSERT INTO knowledge_bases(id,tenant_id,name,description,owner_id) VALUES(?,?,'Configuration fixture','',?)",
        kb,
        tenant,
        member);
    embedding = profile("EMBEDDING", "model-a");
    rerank = profile("RERANK", "rank-a");
    generation = profile("GENERATION", "generate-a");
  }

  String profile(String kind, String model) {
    return profiles.save(actor, null, input(kind, model, "fixture-private-key")).id();
  }

  ModelProfileService.Input input(String kind, String model, String key) {
    return new ModelProfileService.Input(
        "Fixture",
        kind,
        "https://api.deepseek.com",
        model,
        kind.equals("EMBEDDING") ? "revision-a" : "",
        kind.equals("EMBEDDING") ? 512 : null,
        true,
        key,
        0);
  }

  Definition definition(String name, int target) {
    return new Definition(
        name,
        new Parsing(20),
        new Chunking(target, 200, 10),
        new Retrieval("keyword", 4, .2, false),
        new Models(embedding, rerank, generation));
  }

  String configuration(String name, int target) {
    return configurations.create(actor, kb, definition(name, target)).id();
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> upload(String name) throws Exception {
    return (Map<String, Object>)
        uploads.upload(
            actor,
            token,
            kb,
            null,
            Db.id(),
            new MockMultipartFile("file", name, "text/plain", ("synthetic " + name).getBytes()));
  }

  void publish(String config, long revision) {
    configurations.publish(actor, kb, new Publish(config, revision, "synthetic verification"));
  }

  @Test
  void archivedKnowledgeBaseCannotReprocessOrBindAnIndex() throws Exception {
    var uploaded = upload("archived.txt");
    String version = Db.str(uploaded, "version_id");
    publish(configuration("active", 120), 0);
    db.exec("UPDATE knowledge_bases SET status='ARCHIVED' WHERE id=?", kb);
    assertThatThrownBy(() -> drafts.reprocess(actor, version, Db.id()))
        .isInstanceOfSatisfying(
            ApiException.class, error -> assertThat(error.status).isEqualTo(404));
    assertThatThrownBy(
            () ->
                drafts.bindConfiguration(
                    actor, version, new DocumentDraftService.BindConfiguration(0)))
        .isInstanceOfSatisfying(
            ApiException.class, error -> assertThat(error.status).isEqualTo(404));
  }

  @Test
  void editedChunksMustFitTheFrozenModelWindowBeforeSaving() throws Exception {
    var original = definition("native tokenizer", 120);
    var configured =
        new Definition(
            original.name(),
            original.parsing(),
            new Chunking(120, 200, 10, "recursive", true, "provider", 512),
            original.retrieval(),
            original.models());
    publish(configurations.create(actor, kb, configured).id(), 0);
    var uploaded = upload("model-budget.txt");
    String version = Db.str(uploaded, "version_id"), chunk = Db.id();
    db.exec("UPDATE document_versions SET state='PARSED' WHERE id=?", version);
    db.exec(
        "INSERT INTO chunks(id,tenant_id,version_id,ordinal_no,source_text,content,location,token_count) VALUES(?,?,?,0,'source','source','{}',1)",
        chunk,
        tenant,
        version);
    when(worker.call(eq("/internal/v1/tokenize"), any()))
        .thenReturn(Map.of("token_count", 100, "model_token_count", 600, "model_limit", 512));
    assertThatThrownBy(
            () ->
                drafts.edit(
                    actor,
                    token,
                    chunk,
                    new DocumentDraftService.Edit("synthetic edited text", true, 0, "fixture")))
        .isInstanceOfSatisfying(
            ApiException.class, error -> assertThat(error.code).isEqualTo("MODEL_INPUT_TOO_LONG"));
    assertThat(Db.str(db.one("SELECT content FROM chunks WHERE id=?", chunk), "content"))
        .isEqualTo("source");
    verify(worker)
        .call(
            eq("/internal/v1/tokenize"),
            argThat(
                body ->
                    body instanceof Map<?, ?> values
                        && "provider".equals(values.get("model_tokenizer"))
                        && Integer.valueOf(512).equals(values.get("model_maximum"))
                        && values.get("model_configuration")
                            instanceof WorkerProtocolV1.ModelConfiguration));
  }

  @Test
  void immutableDraftsFreezeProfilesHideCredentialsAndRequirePublication() throws Exception {
    String config = configuration("first", 120);
    assertThat(
            Db.str(
                db.one("SELECT * FROM knowledge_bases WHERE id=?", kb), "published_configuration"))
        .isEmpty();
    profiles.save(actor, embedding, input("EMBEDDING", "model-b", null));
    var runtime = configurations.runtime(tenant, config);
    assertThat(runtime.embedding().model()).isEqualTo("model-a");
    assertThat(runtime.embedding().api_key()).isEqualTo("fixture-private-key");
    assertThat(
            Db.str(
                db.one("SELECT * FROM knowledge_configurations WHERE id=?", config), "models_json"))
        .doesNotContain("fixture-private-key");
    String publicJson = json.writeValueAsString(configurations.list(actor, kb));
    assertThat(publicJson).doesNotContain("fixture-private-key", "encrypted_key", "api_key");
    publish(config, 0);
    assertThat(
            Db.str(
                db.one("SELECT * FROM knowledge_bases WHERE id=?", kb), "published_configuration"))
        .isEqualTo(config);
    assertThat(runtime.toString()).doesNotContain("fixture-private-key");
  }

  @Test
  void impactDistinguishesQueryChangesFromRequiredReprocessing() throws Exception {
    String first = configuration("first", 120);
    publish(first, 0);
    String same = configuration("renamed", 120);
    mvc.perform(
            get("/api/v1/knowledge-bases/" + kb + "/configurations/" + same + "/impact")
                .header("Authorization", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reparse_required").value(false))
        .andExpect(jsonPath("$.reindex_required").value(false));
    String changed = configuration("shorter", 80);
    mvc.perform(
            get("/api/v1/knowledge-bases/" + kb + "/configurations/" + changed + "/impact")
                .header("Authorization", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reparse_required").value(true))
        .andExpect(jsonPath("$.reindex_required").value(true));
  }

  @Test
  void publicationRevisionAndRollbackDoNotChangeAlreadyAcceptedJobs() throws Exception {
    String first = configuration("first", 120);
    publish(first, 0);
    var upload = upload("first.txt");
    String second = configuration("second", 80);
    publish(second, 1);
    var claim = tasks.claim(Db.str(upload, "job_id"));
    var runtime = (RuntimeConfiguration) claim.get("configuration");
    assertThat(runtime.id()).isEqualTo(first);
    assertThat(runtime.chunking().target()).isEqualTo(120);
    assertThatThrownBy(() -> publish(first, 1)).isInstanceOf(ApiException.class);
    publish(first, 2);
    assertThat(
            Db.num(
                db.one("SELECT * FROM knowledge_bases WHERE id=?", kb), "configuration_revision"))
        .isEqualTo(3);
    assertThat(db.list("SELECT * FROM knowledge_configuration_publications WHERE kb_id=?", kb))
        .hasSize(3);
  }

  @Test
  void reprocessingCreatesNewVersionWhileOldPublicationAndConfigurationRemain() throws Exception {
    String first = configuration("first", 120);
    publish(first, 0);
    var upload = upload("original.txt");
    String old = Db.str(upload, "version_id"), document = Db.str(upload, "document_id");
    db.exec("UPDATE document_versions SET state='READY',ever_published=TRUE WHERE id=?", old);
    db.exec("UPDATE documents SET published_version=? WHERE id=?", old, document);
    String second = configuration("second", 80);
    publish(second, 1);
    @SuppressWarnings("unchecked")
    var result = (Map<String, Object>) drafts.reprocess(actor, old, Db.id());
    var next = db.one("SELECT * FROM document_versions WHERE id=?", Db.str(result, "version_id"));
    assertThat(Db.str(next, "configuration_id")).isEqualTo(second);
    assertThat(
            Db.str(db.one("SELECT * FROM document_versions WHERE id=?", old), "configuration_id"))
        .isEqualTo(first);
    assertThat(Db.str(db.one("SELECT * FROM documents WHERE id=?", document), "published_version"))
        .isEqualTo(old);
  }

  @Test
  void crossTenantProfilesConfigurationsAndWrongModelKindsAreRejected() throws Exception {
    String config = configuration("first", 120);
    String stranger = Db.id();
    db.exec("INSERT INTO tenants(id,name) VALUES(?,'stranger')", stranger);
    var other = new Actor(stranger, member, "MEMBER", "OWNER");
    assertThatThrownBy(() -> configurations.runtime(stranger, config))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> configurations.list(other, kb)).isInstanceOf(ApiException.class);
    Definition wrong =
        new Definition(
            "wrong",
            new Parsing(20),
            new Chunking(120, 200, 10),
            new Retrieval("hybrid", 4, null, false),
            new Models(generation, rerank, generation));
    assertThatThrownBy(() -> configurations.create(actor, kb, wrong))
        .isInstanceOf(IllegalArgumentException.class);
    mvc.perform(
            post("/api/v1/knowledge-bases/" + kb + "/configurations")
                .header("Authorization", token)
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "name",
                            "invalid",
                            "parsing",
                            Map.of("pdf_page_limit", 501),
                            "chunking",
                            Map.of("target", 100, "maximum", 200, "overlap", 10),
                            "retrieval",
                            Map.of("mode", "hybrid", "limit", 4, "allow_degraded", false),
                            "models",
                            definition("x", 100).models()))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void authenticatedEncryptionBindsTenantAndProfileAndCorruptionFailsClosed() {
    String encrypted = vault.encrypt(tenant, embedding, "fixture-private-key");
    assertThat(vault.decrypt(tenant, embedding, encrypted)).isEqualTo("fixture-private-key");
    assertThatThrownBy(() -> vault.decrypt(Db.id(), embedding, encrypted))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> vault.decrypt(tenant, Db.id(), encrypted))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> vault.decrypt(tenant, embedding, encrypted + "broken"))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void JavaEmbeddingIdentityMatchesPersistedPythonAlgorithm() {
    var model =
        new WorkerProtocolV1.ModelConfiguration(
            "EMBEDDING",
            "https://model.invalid/v1",
            "snapshot-model",
            "immutable-sha",
            512,
            "private");
    assertThat(configurations.modelIdentity(model))
        .isEqualTo("8b183c56b3f2c2da0565efe5d126b1fe9252968bed3112717c75686eaeb3fe26");
  }

  @Test
  void queryKeepsItsConfigurationWhenAnotherVersionIsPublishedMidRequest() throws Exception {
    String first = configuration("first", 120);
    publish(first, 0);
    var upload = upload("published.txt");
    String version = Db.str(upload, "version_id"), document = Db.str(upload, "document_id");
    db.exec(
        "UPDATE document_versions SET state='READY',ever_published=TRUE,model_identity=? WHERE id=?",
        configurations.modelIdentity(configurations.runtime(tenant, first).embedding()),
        version);
    db.exec("UPDATE documents SET published_version=? WHERE id=?", version, document);
    var query = new RetrievalService.Query("synthetic", null, List.of(kb), null, 6, false, null);
    var scope = retrieval.scope(actor, query);
    String second = configuration("second", 80);
    publish(second, 1);
    assertThat(scope.configuration().runtime().id()).isEqualTo(first);
    when(worker.call(eq("/internal/v1/recall"), any()))
        .thenReturn(
            Map.of("dense", List.of(), "bm25", List.of(), "fused", List.of(), "degraded", false));
    var result = retrieval.search(actor, query, scope, token);
    assertThat(result.get("configuration_id")).isEqualTo(first);
    var request = org.mockito.ArgumentCaptor.forClass(Object.class);
    verify(worker).call(eq("/internal/v1/recall"), request.capture());
    assertThat(((Map<?, ?>) request.getValue()).get("mode")).isEqualTo("keyword");
  }

  @Test
  void legacyIndexBindingRequiresExactIdentityAndCanOnlyHappenOnce() throws Exception {
    var upload = upload("legacy.txt");
    String version = Db.str(upload, "version_id");
    String first = configuration("first", 120);
    publish(first, 0);
    db.exec(
        "UPDATE document_versions SET state='READY',model_identity='unknown' WHERE id=?", version);
    assertThatThrownBy(
            () ->
                drafts.bindConfiguration(
                    actor, version, new DocumentDraftService.BindConfiguration(0)))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code).isEqualTo("INDEX_MODEL_MISMATCH"));
    db.exec(
        "UPDATE document_versions SET model_identity=? WHERE id=?",
        configurations.modelIdentity(configurations.runtime(tenant, first).embedding()),
        version);
    drafts.bindConfiguration(actor, version, new DocumentDraftService.BindConfiguration(0));
    assertThat(
            Db.str(
                db.one("SELECT * FROM document_versions WHERE id=?", version), "configuration_id"))
        .isEqualTo(first);
    assertThatThrownBy(
            () ->
                drafts.bindConfiguration(
                    actor, version, new DocumentDraftService.BindConfiguration(1)))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void staleModelProfileRevisionCannotSilentlyChangeANewConfiguration() {
    profiles.save(actor, embedding, input("EMBEDDING", "changed-model", null));
    assertThatThrownBy(() -> configuration("stale", 120))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code).isEqualTo("MODEL_PROFILE_CHANGED"));
    assertThat(db.list("SELECT * FROM knowledge_configurations WHERE kb_id=?", kb)).isEmpty();
  }

  @Test
  void indexCompletionCannotBindDifferentModelFromTheTaskSnapshot() throws Exception {
    String first = configuration("first", 120);
    publish(first, 0);
    var uploaded = upload("index.txt");
    String version = Db.str(uploaded, "version_id");
    db.exec("UPDATE document_versions SET state='PARSED' WHERE id=?", version);
    @SuppressWarnings("unchecked")
    var job = (Map<String, Object>) drafts.index(actor, version, Db.id());
    String id = Db.str(job, "job_id");
    var claim = tasks.claim(id);
    String lease = Db.str(claim, "lease_token");
    assertThatThrownBy(
            () ->
                tasks.complete(id, lease, Map.of("verified", true, "model_identity", "different")))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code).isEqualTo("INDEX_MODEL_MISMATCH"));
    assertThat(Db.str(db.one("SELECT * FROM jobs WHERE id=?", id), "state")).isEqualTo("RUNNING");
    db.exec(
        "INSERT INTO chunks(id,tenant_id,version_id,ordinal_no,source_text,content,location,token_count) VALUES(?,?,?,0,'synthetic','synthetic','{}',1)",
        Db.id(),
        tenant,
        version);
    String callId = Db.id();
    tasks.recordModelCall(id, lease, callId, new IndexAccountingService.Call("STARTED", 1, null));
    tasks.recordModelCall(id, lease, callId, new IndexAccountingService.Call("SUCCEEDED", 1, 7L));
    var completion =
        Map.<String, Object>of(
            "verified",
            true,
            "model_identity",
            configurations.modelIdentity(configurations.runtime(tenant, first).embedding()),
            "indexed_chunks",
            1,
            "embedded_texts",
            1,
            "reused_chunks",
            0,
            "embedding_tokens",
            7,
            "generation_id",
            claim.get("generation_id"),
            "manifest",
            IndexManifest.digest(
                IndexManifest.entries(
                    db.list("SELECT id,content FROM chunks WHERE version_id=?", version))));
    var invalid = new HashMap<>(completion);
    invalid.put("embedding_tokens", 0);
    assertThatThrownBy(() -> tasks.complete(id, lease, invalid))
        .isInstanceOf(IllegalArgumentException.class);
    tasks.complete(id, lease, completion);
    tasks.recordModelCall(id, lease, callId, new IndexAccountingService.Call("SUCCEEDED", 1, 7L));
    assertThat(
            db.list(
                "SELECT * FROM usage_events WHERE tenant_id=? AND resource_type='EMBEDDING_TOKENS'",
                tenant))
        .hasSize(1);
    assertThatThrownBy(
            () ->
                tasks.recordModelCall(
                    id, Db.id(), callId, new IndexAccountingService.Call("SUCCEEDED", 1, 7L)))
        .isInstanceOf(ApiException.class);
    assertThat(Db.str(db.one("SELECT * FROM jobs WHERE id=?", id), "state")).isEqualTo("DONE");
  }

  @Test
  void cancelledTasksAcceptOnlyExistingCallOutcomesWithoutRestoringTheLease() throws Exception {
    String config = configuration("accounting", 120);
    publish(config, 0);
    String version = Db.str(upload("calls.txt"), "version_id");
    db.exec("UPDATE document_versions SET state='PARSED' WHERE id=?", version);
    @SuppressWarnings("unchecked")
    var job = (Map<String, Object>) drafts.index(actor, version, Db.id());
    String jobId = Db.str(job, "job_id"),
        lease = Db.str(tasks.claim(jobId), "lease_token"),
        call = Db.id();
    tasks.recordModelCall(jobId, lease, call, new IndexAccountingService.Call("STARTED", 2, null));
    tasks.cancel(actor, jobId);
    tasks.recordModelCall(
        jobId, lease, call, new IndexAccountingService.Call("SUCCEEDED", 2, null));
    assertThat(db.list("SELECT * FROM usage_events WHERE tenant_id=?", tenant)).isEmpty();
    assertThat(Db.str(db.one("SELECT * FROM jobs WHERE id=?", jobId), "state"))
        .isEqualTo("CANCELLED");
    assertThatThrownBy(
            () ->
                tasks.recordModelCall(
                    jobId, lease, Db.id(), new IndexAccountingService.Call("STARTED", 1, null)))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                tasks.recordModelCall(
                    jobId, lease, Db.id(), new IndexAccountingService.Call("SUCCEEDED", 1, 2L)))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                tasks.recordModelCall(
                    jobId, lease, call, new IndexAccountingService.Call("SUCCEEDED", 2, 3L)))
        .isInstanceOf(ApiException.class);
  }

  String publishedIndexFixture() throws Exception {
    String config = configuration("rebuild", 120);
    publish(config, 0);
    String version = Db.str(upload("rebuild.txt"), "version_id");
    db.exec("UPDATE jobs SET state='DONE' WHERE version_id=?", version);
    db.exec(
        "UPDATE document_versions SET state='READY',ever_published=TRUE,model_identity=? WHERE id=?",
        configurations.modelIdentity(configurations.runtime(tenant, config).embedding()),
        version);
    db.exec(
        "INSERT INTO chunks(id,tenant_id,version_id,ordinal_no,source_text,content,location,token_count) VALUES(?,?,?,0,'synthetic','synthetic','{}',1)",
        Db.id(),
        tenant,
        version);
    return version;
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> rebuilding(String version, String previous) {
    var job =
        (Map<String, Object>)
            indexes.rebuild(
                actor,
                version,
                Db.id(),
                new IndexMaintenanceService.Rebuild(0L, previous, "synthetic rebuild"));
    return tasks.claim(Db.str(job, "job_id"));
  }

  Map<String, Object> verifiedGeneration(String version, Map<String, Object> claim) {
    return Map.of(
        "verified",
        true,
        "model_identity",
        Db.str(db.one("SELECT * FROM document_versions WHERE id=?", version), "model_identity"),
        "generation_id",
        claim.get("generation_id"),
        "manifest",
        IndexManifest.digest(
            IndexManifest.entries(
                db.list("SELECT id,content FROM chunks WHERE version_id=?", version))),
        "indexed_chunks",
        1,
        "embedded_texts",
        0,
        "reused_chunks",
        1,
        "embedding_tokens",
        0);
  }

  @Test
  void rebuildSwitchesOnlyVerifiedGenerationAndFailedRetryKeepsOldIndex() throws Exception {
    String version = publishedIndexFixture();
    var first = rebuilding(version, null);
    assertThat(Db.str(db.one("SELECT * FROM document_versions WHERE id=?", version), "state"))
        .isEqualTo("READY");
    var invalid = new HashMap<>(verifiedGeneration(version, first));
    invalid.put("manifest", "0".repeat(64));
    assertThatThrownBy(
            () -> tasks.complete(Db.str(first, "id"), Db.str(first, "lease_token"), invalid))
        .isInstanceOf(ApiException.class);
    tasks.complete(
        Db.str(first, "id"), Db.str(first, "lease_token"), verifiedGeneration(version, first));
    String previous = Db.str(first, "generation_id");
    var second = rebuilding(version, previous);
    tasks.failed(
        Db.str(second, "id"), Db.str(second, "lease_token"), "PROCESSING_UNAVAILABLE", true);
    assertThat(
            Db.str(
                db.one("SELECT * FROM document_versions WHERE id=?", version),
                "active_index_generation"))
        .isEqualTo(previous);
    assertThat(Db.str(db.one("SELECT * FROM document_versions WHERE id=?", version), "state"))
        .isEqualTo("READY");
    var retry = tasks.claim(Db.str(second, "id"));
    assertThat(retry.get("generation_id")).isNotEqualTo(second.get("generation_id"));
    assertThatThrownBy(
            () ->
                tasks.complete(
                    Db.str(second, "id"),
                    Db.str(second, "lease_token"),
                    verifiedGeneration(version, second)))
        .isInstanceOf(ApiException.class);
    tasks.complete(
        Db.str(retry, "id"), Db.str(retry, "lease_token"), verifiedGeneration(version, retry));
    assertThat(Db.str(db.one("SELECT * FROM index_generations WHERE id=?", previous), "state"))
        .isEqualTo("RETIRED");
    assertThat(
            Db.str(
                db.one("SELECT * FROM index_generations WHERE id=?", second.get("generation_id")),
                "state"))
        .isEqualTo("FAILED");
    assertThat(
            Db.str(
                db.one("SELECT * FROM document_versions WHERE id=?", version),
                "active_index_generation"))
        .isEqualTo(retry.get("generation_id"));
  }

  @Test
  void indexCheckRejectsFalseSuccessAndReauthorizesAfterWorker() throws Exception {
    String version = publishedIndexFixture();
    var report =
        new HashMap<String, Object>(
            Map.of(
                "consistent",
                true,
                "expected_count",
                1,
                "actual_count",
                1,
                "missing_count",
                0,
                "extra_count",
                0,
                "mismatched_count",
                0,
                "manifest",
                "0".repeat(64),
                "samples",
                Map.of()));
    when(worker.call(eq("/internal/v1/index/verify"), any())).thenReturn(report);
    assertThatThrownBy(() -> indexes.inspect(actor, token, version))
        .isInstanceOf(ApiException.class);
    report.put(
        "manifest",
        IndexManifest.digest(
            IndexManifest.entries(
                db.list("SELECT id,content FROM chunks WHERE version_id=?", version))));
    indexes.inspect(actor, token, version);
    assertThat(db.list("SELECT * FROM index_checks WHERE version_id=?", version)).hasSize(1);
    when(worker.call(eq("/internal/v1/index/verify"), any()))
        .thenAnswer(
            invocation -> {
              db.exec("UPDATE credentials SET active=FALSE WHERE tenant_id=?", tenant);
              return report;
            });
    assertThatThrownBy(() -> indexes.inspect(actor, token, version))
        .isInstanceOf(ApiException.class);
    assertThat(db.list("SELECT * FROM index_checks WHERE version_id=?", version)).hasSize(1);
  }

  @Test
  void archivedKnowledgeCannotStartIndexMaintenance() throws Exception {
    String version = publishedIndexFixture();
    db.exec("UPDATE knowledge_bases SET status='ARCHIVED' WHERE id=?", kb);
    assertThatThrownBy(() -> rebuilding(version, null)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> indexes.inspect(actor, token, version))
        .isInstanceOf(ApiException.class);
    verify(worker, never()).call(eq("/internal/v1/index/verify"), any());
  }
}
