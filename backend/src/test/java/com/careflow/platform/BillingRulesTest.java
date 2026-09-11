package com.careflow.platform;

import static com.careflow.platform.Db.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class BillingRulesTest extends ContentTestSupport {
  @Autowired BillingRulesService billing;
  @Autowired CostAccountingService costs;
  @Autowired ImportCostService estimates;
  @Autowired DocumentUploadService uploads;

  @SuppressWarnings("unchecked")
  String rule(String query) {
    return str(
        (Map<String, Object>)
            billing.create(
                actor,
                new BillingRulesService.Input(
                    "Synthetic prices",
                    "CNY",
                    Map.of(
                        "QUERY", new BigDecimal(query), "PROCESSING_TASK", new BigDecimal("0.3")),
                    Map.of("EMBEDDING_TOKEN", new BigDecimal("0.000001")))),
        "id");
  }

  @Test
  @SuppressWarnings("unchecked")
  void priceChangesNeverRepriceExistingExecutionsAndDeactivationIsNotFree() {
    String first = rule("0.1");
    billing.activate(actor, new BillingRulesService.Activate(UUID.fromString(first), 0));
    String old = reservedRequest(actor);
    reservations.settle(actor, old, true);
    String next = rule("0.2");
    billing.activate(actor, new BillingRulesService.Activate(UUID.fromString(next), 1));
    String fresh = reservedRequest(actor);
    reservations.settle(actor, fresh, true);
    var before = (Map<String, Object>) costs.request(actor, null, UUID.fromString(old));
    var after = (Map<String, Object>) costs.request(actor, null, UUID.fromString(fresh));
    assertThat((Map<String, Object>) before.get("customer")).containsEntry("amount", "0.1");
    assertThat((Map<String, Object>) after.get("customer")).containsEntry("amount", "0.2");
    billing.activate(actor, new BillingRulesService.Activate(null, 2));
    String unknown = reservedRequest(actor);
    reservations.settle(actor, unknown, true);
    var missing = (Map<String, Object>) costs.request(actor, null, UUID.fromString(unknown));
    assertThat((Map<String, Object>) missing.get("customer"))
        .containsEntry("amount", null)
        .containsEntry("state", "UNCONFIGURED");
    assertThat(costs.request(actor, null, UUID.fromString(old)).toString()).contains("amount=0.1");
  }

  @Test
  void missingProviderUsageCannotBecomeCompleteZero() {
    var result =
        CostCalculation.calculate(
            Map.of("EMBEDDING_TOKEN", new BigDecimal("0.000001")),
            Map.of("EMBEDDING_TOKEN", new CostCalculation.Quantity(17L, true)),
            true);
    assertThat(result)
        .containsEntry("amount", null)
        .containsEntry("state", "PARTIAL")
        .containsEntry("known_subtotal", "0.000017");
    assertThat(
            CostCalculation.calculate(
                Map.of(), Map.of("QUERY", CostCalculation.Quantity.known(1)), true))
        .containsEntry("amount", null);
  }

  @Test
  void invalidPricesAndCrossTenantRuleActivationAreRejected() {
    assertThatThrownBy(
            () ->
                billing.create(
                    actor,
                    new BillingRulesService.Input(
                        "Bad", "CNY", Map.of("QUERY", new BigDecimal("-1")), Map.of())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> billing.activate(actor, new BillingRulesService.Activate(UUID.randomUUID(), 0)))
        .isInstanceOf(ApiException.class);
    String id = rule("0.1");
    billing.activate(actor, new BillingRulesService.Activate(UUID.fromString(id), 0));
    assertThatThrownBy(() -> billing.activate(actor, new BillingRulesService.Activate(null, 0)))
        .isInstanceOf(ApiException.class);
  }

  @Test
  @SuppressWarnings("unchecked")
  void estimateCreatesNoTaskAndChangedPricePreventsStorageWrite() throws Exception {
    String kb =
        str(
            db.one(
                "SELECT d.kb_id FROM documents d JOIN document_versions v ON v.document_id=d.id WHERE v.id=?",
                version),
            "kb_id");
    var file =
        new MockMultipartFile(
            "file",
            "estimate.txt",
            "text/plain",
            "Synthetic estimate".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var estimate = (Map<String, Object>) estimates.estimate(actor, token, kb, file);
    assertThat(estimate).containsEntry("basis", "ESTIMATE").containsEntry("source_bytes", 18);
    assertThat(db.list("SELECT id FROM jobs WHERE tenant_id=?", tenant)).isEmpty();
    String id = rule("0.1");
    billing.activate(actor, new BillingRulesService.Activate(UUID.fromString(id), 0));
    assertThatThrownBy(() -> uploads.upload(actor, token, kb, null, Db.id(), file, 0L))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code).isEqualTo("BILLING_RULE_CHANGED"));
    verifyNoInteractions(blobs);
  }

  @Test
  @SuppressWarnings("unchecked")
  void pdfEstimateKeepsUnknownOcrPagesWithoutUnboxingNull() throws Exception {
    String kb =
        str(
            db.one(
                "SELECT d.kb_id FROM documents d JOIN document_versions v ON v.document_id=d.id WHERE v.id=?",
                version),
            "kb_id");
    var file =
        new MockMultipartFile(
            "file", "estimate.pdf", "application/pdf", "%PDF-1.4\nsynthetic\n".getBytes());

    var estimate = (Map<String, Object>) estimates.estimate(actor, token, kb, file);

    assertThat(estimate).containsEntry("estimated_ocr_pages", null);
    assertThat(db.list("SELECT id FROM jobs WHERE tenant_id=?", tenant)).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void jobFeeIsFrozenAndProviderBreakdownIsRestricted() {
    String first = rule("0.1");
    billing.activate(actor, new BillingRulesService.Activate(UUID.fromString(first), 0));
    String job = id();
    db.exec(
        "INSERT INTO jobs(id,tenant_id,version_id,kind,state,request_key,quota_counted) VALUES(?,?,?,'PARSE','DONE',?,TRUE)",
        job,
        tenant,
        version,
        id());
    billing.attachJob(tenant, job, 20);
    billing.activate(actor, new BillingRulesService.Activate(null, 1));
    var cost = (Map<String, Object>) costs.job(actor, UUID.fromString(job));
    assertThat(cost).containsEntry("rule_id", first).containsEntry("basis", "ACTUAL");
    assertThat((Map<String, Object>) cost.get("customer")).containsEntry("amount", "0.3");
    for (String role : List.of("DEVELOPER", "USER", "APPLICATION")) {
      var viewer =
          new Identity.Actor(
              tenant, actor.subject(), role.equals("APPLICATION") ? "APP" : "MEMBER", role);
      assertThat(
              costs.quote(
                  viewer,
                  first,
                  Map.of("QUERY", CostCalculation.Quantity.known(1)),
                  Map.of("EMBEDDING_TOKEN", CostCalculation.Quantity.known(10)),
                  "ACTUAL"))
          .containsKey("customer")
          .doesNotContainKey("provider");
    }
  }
}
