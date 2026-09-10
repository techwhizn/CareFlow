package com.careflow.platform;

import static com.careflow.platform.Db.*;
import static org.assertj.core.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class RetrievalAccountingTest extends ContentTestSupport {
  @Autowired RetrievalAccounting accounting;
  @Autowired RetrievalService retrieval;
  @Autowired ModelUsageReadService usage;

  @Test
  void keepsReportedCostWhenDeliveryFailsAndMissingUsageIsNotZero() {
    String request = retrieval.reserve(actor, "synthetic-call", "");
    accounting.call(
        tenant,
        request,
        "EMBEDDING",
        null,
        1,
        () -> Map.of("usage", Map.of("state", "REPORTED", "total_tokens", 23)));
    accounting.call(
        tenant, request, "RERANK", null, 4, () -> Map.of("usage", Map.of("state", "NOT_REPORTED")));
    retrieval.settle(actor, request, false);
    assertThat(usage.tenant(actor).toString())
        .contains("known_tokens=23", "unknown_usage_calls=1")
        .doesNotContain("synthetic-call");
    var calls =
        db.list("SELECT * FROM retrieval_model_calls WHERE request_id=? ORDER BY stage", request);
    assertThat(num(calls.get(0), "total_tokens")).isEqualTo(23);
    assertThat(calls.get(1).get("total_tokens")).isNull();
    assertThat(str(calls.get(1), "usage_state")).isEqualTo("NOT_REPORTED");
    assertThat(num(db.one("SELECT queries_used FROM tenants WHERE id=?", tenant), "queries_used"))
        .isZero();
  }

  @Test
  void failureRemainsUnknownAndTenantMismatchCannotStartCall() {
    String request = retrieval.reserve(actor, "synthetic-call", "");
    assertThatThrownBy(
            () ->
                accounting.call(
                    tenant,
                    request,
                    "RERANK",
                    null,
                    2,
                    () -> {
                      throw new IllegalStateException("synthetic model failure");
                    }))
        .isInstanceOf(IllegalStateException.class);
    var call = db.one("SELECT * FROM retrieval_model_calls WHERE request_id=?", request);
    assertThat(str(call, "call_state")).isEqualTo("FAILED");
    assertThat(str(call, "usage_state")).isEqualTo("UNKNOWN");
    assertThat(call.get("total_tokens")).isNull();
    assertThatThrownBy(
            () ->
                accounting.call(
                    id(),
                    request,
                    "EMBEDDING",
                    null,
                    1,
                    () -> {
                      throw new AssertionError("must not reach model");
                    }))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void keywordAndEmptyCandidatesRecordExplicitlyNotCalled() {
    String request = retrieval.reserve(actor, "synthetic-call", "");
    accounting.call(tenant, request, "EMBEDDING", null, 0, () -> Map.of());
    var call = db.one("SELECT * FROM retrieval_model_calls WHERE request_id=?", request);
    assertThat(str(call, "usage_state")).isEqualTo("NOT_CALLED");
    assertThat(call.get("total_tokens")).isNull();
  }
}
