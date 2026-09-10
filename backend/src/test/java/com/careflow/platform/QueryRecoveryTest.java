package com.careflow.platform;

import static com.careflow.platform.Db.*;
import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class QueryRecoveryTest extends ContentTestSupport {
  @Autowired GenerationAccounting generation;
  @Autowired QueryRecordService records;

  void expire(String request) {
    db.exec(
        "UPDATE usage_events SET expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE id=?",
        request);
  }

  @Test
  void releasesUncommittedWorkButPreservesActualConsumptionAndAllowsLateReceipt() {
    String request = reservedRequest(actor), active = reservedRequest(actor);
    generation.initialize(tenant, request, null);
    generation.started(tenant, request);
    generation.reported(tenant, request, Map.of("input_tokens", 7));
    expire(request);
    reservations.recover();
    reservations.recover();
    var row = db.one("SELECT state,outcome FROM usage_events WHERE id=?", request);
    assertThat(row).containsEntry("state", "RELEASED").containsEntry("outcome", "RECOVERED");
    assertThat(
            num(
                db.one("SELECT queries_reserved FROM tenants WHERE id=?", tenant),
                "queries_reserved"))
        .isEqualTo(1);
    assertThatThrownBy(() -> reservations.result(actor, request, true))
        .isInstanceOf(ApiException.class);
    generation.reported(
        tenant, request, Map.of("input_tokens", 7, "output_tokens", 3, "total_tokens", 10));
    assertThat(
            num(
                db.one("SELECT total_tokens FROM generation_usage WHERE request_id=?", request),
                "total_tokens"))
        .isEqualTo(10);
    reservations.settle(actor, request, true);
    assertThat(num(db.one("SELECT queries_used FROM tenants WHERE id=?", tenant), "queries_used"))
        .isZero();
    reservations.settle(actor, active, false);
  }

  @Test
  void durableResultIsChargedExactlyOnceEvenIfSettlementWasInterrupted() {
    String request = reservedRequest(actor);
    reservations.result(actor, request, true);
    expire(request);
    reservations.recover();
    reservations.settle(actor, request, false);
    reservations.recover();
    assertThat(db.one("SELECT state,outcome FROM usage_events WHERE id=?", request))
        .containsEntry("state", "SETTLED")
        .containsEntry("outcome", "SUCCEEDED");
    var counters = db.one("SELECT queries_used,queries_reserved FROM tenants WHERE id=?", tenant);
    assertThat(num(counters, "queries_used")).isEqualTo(1);
    assertThat(num(counters, "queries_reserved")).isZero();
  }

  @Test
  void expiredRequestCannotSaveSearchRecord() {
    String request = reservedRequest(actor);
    expire(request);
    assertThatThrownBy(
            () ->
                records.save(
                    actor,
                    new RetrievalService.Query(
                        "synthetic", null, List.of(), "keyword", 3, false, null),
                    new RetrievalService.Scope(List.of(), false, "", -1),
                    Map.of("evidence", List.of(), "evidence_status", "NO_MATCH"),
                    request))
        .isInstanceOf(ApiException.class);
    assertThat(db.list("SELECT id FROM query_records WHERE id=?", request)).isEmpty();
    assertThat(
            bool(
                db.one("SELECT result_committed FROM usage_events WHERE id=?", request),
                "result_committed"))
        .isFalse();
  }

  @Test
  void lateCompletionCannotChargeAnExpiredUncommittedReservation() {
    String request = reservedRequest(actor);
    expire(request);
    reservations.settle(actor, request, true);
    assertThat(db.one("SELECT state FROM usage_events WHERE id=?", request))
        .containsEntry("state", "RELEASED");
    assertThat(num(db.one("SELECT queries_used FROM tenants WHERE id=?", tenant), "queries_used"))
        .isZero();
  }
}
