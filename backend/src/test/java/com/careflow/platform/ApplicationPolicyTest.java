package com.careflow.platform;

import static com.careflow.platform.Db.*;
import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class ApplicationPolicyTest extends ContentTestSupport {
  @Autowired ApplicationService applications;
  @Autowired ApplicationConfigurationService configurations;
  @Autowired ModelProfileService profiles;
  @Autowired RetrievalService retrieval;
  @Autowired com.fasterxml.jackson.databind.ObjectMapper json;

  String kb() {
    return str(
        db.one(
            "SELECT d.kb_id FROM documents d JOIN document_versions v ON v.document_id=d.id WHERE v.id=?",
            version),
        "kb_id");
  }

  String profile(String kind, String name) {
    return profiles
        .save(
            actor,
            null,
            new ModelProfileService.Input(
                "Synthetic",
                kind,
                "https://api.deepseek.com",
                name,
                "revision",
                null,
                true,
                "synthetic-private-key",
                0))
        .id();
  }

  String app() {
    @SuppressWarnings("unchecked")
    var created =
        (Map<String, Object>)
            applications.app(
                actor,
                new ApplicationService.Named(
                    "Synthetic app", "", UUID.fromString(actor.subject())));
    String id = str(created, "id");
    db.exec(
        "INSERT INTO permissions(tenant_id,resource_id,subject_id,action) VALUES(?,?,?,'read')",
        tenant,
        kb(),
        id);
    return id;
  }

  ApplicationService.Bind config(String rank, String generation, String language, long revision) {
    return new ApplicationService.Bind(
        List.of(kb()),
        revision,
        false,
        UUID.fromString(actor.subject()),
        new KnowledgeConfiguration.Retrieval("keyword", 3, .3, false),
        new ApplicationPolicy.Models(rank, 0, generation, 0),
        new ApplicationPolicy.Answer(language, "concise", 512, 1, 500));
  }

  @Test
  void twoAppsShareKnowledgeButKeepImmutableIndependentModelsAndPolicy() throws Exception {
    String rank = profile("RERANK", "rank-a"),
        generation = profile("GENERATION", "generate-a"),
        secondGeneration = profile("GENERATION", "generate-b");
    String first = app(), second = app();
    var draft = configurations.create(actor, first, config(rank, generation, "en", 0));
    assertThat(json.writeValueAsString(draft))
        .doesNotContain("synthetic-private-key", "encrypted_key", "models_json");
    assertThatThrownBy(() -> configurations.runtime(tenant, first))
        .isInstanceOf(ApiException.class);
    configurations.publish(
        actor, first, new ApplicationService.ConfigurationPublish(str(draft, "id"), 0));
    configurations.bind(actor, second, config(rank, secondGeneration, "zh", 0));
    profiles.save(
        actor,
        generation,
        new ModelProfileService.Input(
            "Changed",
            "GENERATION",
            "https://api.deepseek.com",
            "generate-new",
            "revision",
            null,
            true,
            "new-synthetic-key",
            0));
    assertThat(configurations.runtime(tenant, first).models().generation().model())
        .isEqualTo("generate-a");
    assertThat(configurations.runtime(tenant, second).models().generation().model())
        .isEqualTo("generate-b");
    assertThat(configurations.runtime(tenant, first).answer().language()).isEqualTo("en");
    assertThat(configurations.runtime(tenant, second).answer().language()).isEqualTo("zh");
    var query = new RetrievalService.Query("合成问题", first, List.of(kb()), null, 6, false, null);
    var scope = retrieval.scope(actor, query);
    assertThat(scope.configuration().retrieval().limit()).isEqualTo(3);
    assertThat(scope.answerPolicy().history_tokens()).isEqualTo(500);
    assertThatThrownBy(
            () ->
                retrieval.scope(
                    actor,
                    new RetrievalService.Query(
                        "合成问题", first, List.of(kb()), "semantic", 6, false, null)))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code).isEqualTo("APPLICATION_POLICY_MISMATCH"));
  }

  @Test
  void rollbackUsesOriginalSnapshotButCannotRestoreRevokedBindings() {
    String rank = profile("RERANK", "rank-a"),
        generation = profile("GENERATION", "generate-a"),
        app = app();
    var old = configurations.create(actor, app, config(rank, generation, "en", 0));
    configurations.publish(
        actor, app, new ApplicationService.ConfigurationPublish(str(old, "id"), 0));
    configurations.bind(actor, app, config(rank, generation, "zh", 1));
    configurations.publish(
        actor, app, new ApplicationService.ConfigurationPublish(str(old, "id"), 2));
    assertThat(configurations.runtime(tenant, app).answer().language()).isEqualTo("en");
    assertThat(configurations.publications(actor, app)).hasSize(3);
    db.exec("DELETE FROM permissions WHERE tenant_id=? AND subject_id=?", tenant, app);
    assertThatThrownBy(
            () ->
                configurations.publish(
                    actor, app, new ApplicationService.ConfigurationPublish(str(old, "id"), 3)))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code).isEqualTo("APP_NOT_AUTHORIZED"));
    assertThat(num(db.one("SELECT revision FROM applications WHERE id=?", app), "revision"))
        .isEqualTo(3);
  }
}
