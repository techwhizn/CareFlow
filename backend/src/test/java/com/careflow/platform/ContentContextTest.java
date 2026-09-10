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
class ContentContextTest {
  @Autowired Db db;
  @Autowired Identity auth;
  @Autowired ModelProfileService profiles;
  @Autowired KnowledgeConfigurationService configurations;
  @Autowired ModelKeyVault vault;
  @Autowired DocumentUploadService uploads;
  @Autowired DocumentDraftService drafts;
  @Autowired Tasks tasks;
  @Autowired DocumentContextService contexts;
  @Autowired DocumentReadService reads;
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

  Map<String, Object> result(String question, String answer) {
    return Map.of(
        "chunks",
        List.of(
            Map.of(
                "source_text",
                "source answer",
                "content",
                answer,
                "location",
                "{\"type\":\"text\",\"page\":1}",
                "token_count",
                5,
                "context_ordinal",
                0)),
        "contexts",
        List.of(
            Map.of(
                "ordinal",
                0,
                "kind",
                "FAQ",
                "source_text",
                "source question and answer",
                "content",
                question + " " + answer,
                "location",
                "{\"type\":\"text\",\"page\":1}",
                "token_count",
                10,
                "question",
                question,
                "alternatives",
                List.of("similar"),
                "answer",
                answer)));
  }

  Map<String, Object> parsed() throws Exception {
    var definition = definition("contexts", 100);
    publish(
        configurations
            .create(
                actor,
                kb,
                new Definition(
                    definition.name(),
                    definition.parsing(),
                    new Chunking(100, 200, 10, "recursive", true, "cl100k_base", 600, "faq", 1600),
                    definition.retrieval(),
                    definition.models()))
            .id(),
        0);
    var uploaded = upload("faq.md");
    var claim = tasks.claim(Db.str(uploaded, "job_id"));
    tasks.complete(
        Db.str(uploaded, "job_id"), Db.str(claim, "lease_token"), result("Question", "Answer"));
    return uploaded;
  }

  @Test
  void parsedRelationshipsAreSameVersionAndDraftCopiesOwnContextIds() throws Exception {
    var uploaded = parsed();
    String version = Db.str(uploaded, "version_id");
    var child = db.one("SELECT * FROM chunks WHERE version_id=?", version);
    var parent = db.one("SELECT * FROM chunk_contexts WHERE id=?", child.get("context_id"));
    assertThat(Db.str(parent, "version_id")).isEqualTo(version);
    assertThat(Db.str(parent, "answer")).isEqualTo("Answer");
    var draft = (Map<?, ?>) drafts.draft(actor, version);
    String next = draft.get("id").toString();
    var copy = db.one("SELECT * FROM chunks WHERE version_id=?", next);
    assertThat(copy.get("context_id")).isNotEqualTo(child.get("context_id"));
    assertThat(
            Db.str(
                db.one("SELECT * FROM chunk_contexts WHERE id=?", copy.get("context_id")),
                "version_id"))
        .isEqualTo(next);
    assertThatThrownBy(
            () ->
                db.exec(
                    "UPDATE chunks SET context_id=? WHERE id=?",
                    child.get("context_id"),
                    copy.get("id")))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void unknownWorkerContextRollsBackWithoutTouchingOtherVersion() throws Exception {
    var first = parsed();
    var second = upload("other.md");
    var lease = tasks.claim(Db.str(second, "job_id"));
    Map<String, Object> invalid =
        Map.of(
            "chunks",
            List.of(
                Map.of(
                    "source_text",
                    "text",
                    "content",
                    "text",
                    "location",
                    "{}",
                    "token_count",
                    1,
                    "context_ordinal",
                    7)));
    assertThatThrownBy(
            () -> tasks.complete(Db.str(second, "job_id"), Db.str(lease, "lease_token"), invalid))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(db.list("SELECT id FROM chunks WHERE version_id=?", second.get("version_id")))
        .isEmpty();
    assertThat(db.list("SELECT id FROM chunks WHERE version_id=?", first.get("version_id")))
        .hasSize(1);
  }

  @Test
  void manualFaqHasNoFabricatedFileLocationAndWholeGroupRevisionIsRecorded() throws Exception {
    var uploaded = parsed();
    String version = Db.str(uploaded, "version_id");
    when(worker.call(eq("/internal/v1/faq/chunk"), any())).thenReturn(result("How?", "Restart."));
    var created =
        (Map<?, ?>)
            contexts.saveFaq(
                actor,
                token,
                version,
                null,
                new DocumentContextService.Faq(
                    0L, "How?", List.of("similar"), "Restart.", "manual fixture"));
    String id = created.get("id").toString();
    var parent = db.one("SELECT * FROM chunk_contexts WHERE id=?", id);
    assertThat(Db.str(parent, "origin")).isEqualTo("MANUAL");
    assertThat(Db.str(parent, "source_text")).isEmpty();
    assertThat(Db.str(parent, "location")).contains("manual").doesNotContain("page");
    var child = db.one("SELECT * FROM chunks WHERE context_id=?", id);
    assertThat(Db.str(child, "source_text")).isEmpty();
    when(worker.call(eq("/internal/v1/faq/chunk"), any()))
        .thenReturn(result("How now?", "Reconnect."));
    contexts.saveFaq(
        actor,
        token,
        version,
        id,
        new DocumentContextService.Faq(1L, "How now?", List.of(), "Reconnect.", "correct answer"));
    assertThat(db.list("SELECT id FROM chunks WHERE id=?", child.get("id"))).isEmpty();
    assertThat(db.list("SELECT id FROM chunk_context_revisions WHERE context_id=?", id)).hasSize(1);
    assertThat(
            Db.num(
                db.one("SELECT revision FROM document_versions WHERE id=?", version), "revision"))
        .isEqualTo(2);
    assertThatThrownBy(
            () ->
                contexts.saveFaq(
                    actor,
                    token,
                    version,
                    id,
                    new DocumentContextService.Faq(1L, "stale", List.of(), "answer", "stale")))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void revokedCredentialDuringModelWorkCannotSaveFaq() throws Exception {
    var uploaded = parsed();
    String version = Db.str(uploaded, "version_id");
    when(worker.call(eq("/internal/v1/faq/chunk"), any()))
        .thenAnswer(
            invocation -> {
              db.exec(
                  "UPDATE credentials SET active=FALSE WHERE digest=?",
                  Identity.hash(token.substring(7)));
              return result("Q", "A");
            });
    assertThatThrownBy(
            () ->
                contexts.saveFaq(
                    actor,
                    token,
                    version,
                    null,
                    new DocumentContextService.Faq(0L, "Q", List.of(), "A", "fixture")))
        .isInstanceOfSatisfying(
            ApiException.class, error -> assertThat(error.status).isEqualTo(401));
    assertThat(
            db.list(
                "SELECT id FROM chunk_contexts WHERE version_id=? AND origin='MANUAL'", version))
        .isEmpty();
  }

  @Test
  void detachRequiresVersionRevisionAndPublishedGroupsCannotMutate() throws Exception {
    var uploaded = parsed();
    String version = Db.str(uploaded, "version_id");
    String context =
        Db.str(db.one("SELECT * FROM chunk_contexts WHERE version_id=?", version), "id");
    assertThatThrownBy(
            () ->
                contexts.detach(
                    actor, token, version, context, new DocumentContextService.Change(7L, "stale")))
        .isInstanceOf(ApiException.class);
    contexts.detach(
        actor, token, version, context, new DocumentContextService.Change(0L, "separate editing"));
    assertThat(
            db.list("SELECT id FROM chunks WHERE version_id=? AND context_id IS NOT NULL", version))
        .isEmpty();
    db.exec("UPDATE document_versions SET ever_published=TRUE WHERE id=?", version);
    assertThatThrownBy(
            () ->
                contexts.saveFaq(
                    actor,
                    token,
                    version,
                    null,
                    new DocumentContextService.Faq(1L, "Q", List.of(), "A", "immutable")))
        .isInstanceOfSatisfying(
            ApiException.class, error -> assertThat(error.code).isEqualTo("IMMUTABLE_PUBLICATION"));
    verifyNoInteractions(worker);
  }
}
