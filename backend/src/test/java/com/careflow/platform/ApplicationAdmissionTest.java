package com.careflow.platform;

import static com.careflow.platform.Db.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class ApplicationAdmissionTest extends ContentTestSupport {
  @Autowired ApplicationAdmissionService admission;
  @Autowired RetrievalService retrieval;

  String app(int minute, int concurrent) {
    String id = id();
    db.exec(
        "INSERT INTO applications(id,tenant_id,name,description,requests_per_minute,concurrent_requests) VALUES(?,?,'Synthetic','',?,?)",
        id,
        tenant,
        minute,
        concurrent);
    return id;
  }

  @Test
  void rejectsWithoutReservingAndSettlementReleasesConcurrencyButNotRate() {
    String app = app(2, 1);
    String first = retrieval.reserve(actor, "first", app);
    assertThatThrownBy(() -> retrieval.reserve(actor, "second", app))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code).isEqualTo("APPLICATION_CONCURRENCY_LIMIT"));
    assertThat(
            num(
                db.one("SELECT queries_reserved FROM tenants WHERE id=?", tenant),
                "queries_reserved"))
        .isEqualTo(1);
    retrieval.settle(actor, first, false);
    String second = retrieval.reserve(actor, "second", app);
    retrieval.settle(actor, second, true);
    assertThatThrownBy(() -> retrieval.reserve(actor, "third", app))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code).isEqualTo("APPLICATION_RATE_LIMIT"));
    assertThat(
            num(
                db.one("SELECT queries_reserved,queries_used FROM tenants WHERE id=?", tenant),
                "queries_reserved"))
        .isZero();
    assertThat(num(db.one("SELECT queries_used FROM tenants WHERE id=?", tenant), "queries_used"))
        .isEqualTo(1);
    assertThat(retrieval.reserve(actor, "another-app", app(2, 1))).isNotBlank();
  }

  @Test
  void oldSettledCallsExpireFromWindowAndConfigurationUsesRevision() {
    String app = app(1, 1);
    String first = retrieval.reserve(actor, "first", app);
    retrieval.settle(actor, first, true);
    db.exec(
        "UPDATE usage_events SET created_at=TIMESTAMPADD(SECOND,-61,CURRENT_TIMESTAMP) WHERE id=?",
        first);
    assertThat(retrieval.reserve(actor, "next", app)).isNotBlank();
    admission.update(actor, app, new ApplicationAdmissionService.Limits(20, 2, 0));
    assertThatThrownBy(
            () -> admission.update(actor, app, new ApplicationAdmissionService.Limits(30, 3, 0)))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(409));
    assertThatThrownBy(() -> admission.limits(actor, id())).isInstanceOf(ApiException.class);
  }
}
