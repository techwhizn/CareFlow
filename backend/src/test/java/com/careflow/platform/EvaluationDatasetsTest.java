package com.careflow.platform;

import static com.careflow.platform.Db.*;
import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class EvaluationDatasetsTest extends ContentTestSupport {
  @Autowired EvaluationDatasets datasets;
  @Autowired ContentPurgeService purge;

  @SuppressWarnings("unchecked")
  String dataset() {
    String kb =
        str(
            db.one(
                "SELECT d.kb_id FROM documents d JOIN document_versions v ON v.document_id=d.id WHERE v.id=?",
                version),
            "kb_id");
    return str(
        (Map<String, Object>)
            datasets.create(
                actor, new EvaluationDatasets.Create(UUID.fromString(kb), "Synthetic eval")),
        "id");
  }

  EvaluationDatasets.Case item(String evidence) {
    return new EvaluationDatasets.Case(
        "case-1",
        "Synthetic question",
        "Synthetic answer",
        "DIRECT",
        "ANSWERABLE",
        evidence == null ? List.of() : List.of(UUID.fromString(evidence)),
        "MEMBER",
        UUID.fromString(actor.subject()));
  }

  @Test
  @SuppressWarnings("unchecked")
  void immutableVersionsAndReviewResetAreEnforced() {
    String id = dataset(), evidence = chunk(0, "Synthetic source", 2, true, "p1");
    var first =
        (Map<String, Object>)
            datasets.save(actor, id, new EvaluationDatasets.Version(0, List.of(item(evidence))));
    String v = str(first, "id");
    var reviewed =
        (Map<String, Object>)
            datasets.review(
                actor,
                id,
                v,
                "case-1",
                new EvaluationDatasets.Review("APPROVED", "Synthetic test only"));
    assertThat((List<?>) reviewed.get("reviews")).hasSize(1);
    assertThatThrownBy(
            () ->
                datasets.review(
                    actor, id, v, "case-1", new EvaluationDatasets.Review("REJECTED", "")))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                datasets.save(
                    actor, id, new EvaluationDatasets.Version(0, List.of(item(evidence)))))
        .isInstanceOf(ApiException.class);
    var next =
        (Map<String, Object>)
            datasets.save(actor, id, new EvaluationDatasets.Version(1, List.of(item(evidence))));
    assertThat((List<?>) next.get("reviews")).isEmpty();
    assertThat((List<?>) datasets.version(actor, id, v).get("reviews")).hasSize(1);
  }

  @Test
  @SuppressWarnings("unchecked")
  void evidenceIsRequiredForApprovalAndPurgeRemovesCopiedContent() {
    String id = dataset();
    var empty =
        (Map<String, Object>)
            datasets.save(actor, id, new EvaluationDatasets.Version(0, List.of(item(null))));
    assertThatThrownBy(
            () ->
                datasets.review(
                    actor,
                    id,
                    str(empty, "id"),
                    "case-1",
                    new EvaluationDatasets.Review("APPROVED", "")))
        .isInstanceOf(ApiException.class);
    String evidence = chunk(0, "Synthetic source", 2, true, "p1");
    var complete =
        (Map<String, Object>)
            datasets.save(actor, id, new EvaluationDatasets.Version(1, List.of(item(evidence))));
    purge.version(tenant, version);
    assertThat(
            db.list("SELECT id FROM evaluation_dataset_versions WHERE id=?", str(complete, "id")))
        .isEmpty();
  }

  @Test
  void crossTenantAndApplicationAccessAreRejected() {
    String id = dataset();
    var other = new Identity.Actor(Db.id(), Db.id(), "MEMBER", "OWNER");
    assertThatThrownBy(() -> datasets.detail(other, id)).isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () -> datasets.list(new Identity.Actor(tenant, actor.subject(), "APP", "APPLICATION")))
        .isInstanceOf(ApiException.class);
  }
}
