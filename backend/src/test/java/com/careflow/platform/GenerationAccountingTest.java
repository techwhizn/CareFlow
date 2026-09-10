package com.careflow.platform;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class GenerationAccountingTest extends ContentTestSupport {
  @Autowired GenerationAccounting accounting;

  String reservation(String tenant) {
    String id = Db.id();
    db.exec(
        "INSERT INTO usage_events(id,tenant_id,subject_id,request_key,resource_type,amount,state) VALUES(?,?,?,?, 'QUERY',1,'RESERVED')",
        id,
        tenant,
        Db.id(),
        Db.id());
    accounting.initialize(tenant, id, null);
    return id;
  }

  @Test
  void cancellationKeepsKnownConsumptionAndUnreportedCallsStayUnknown() {
    String tenant = Db.id(),
        known = reservation(tenant),
        unknown = reservation(tenant),
        unused = reservation(tenant);
    accounting.started(tenant, known);
    accounting.reported(tenant, known, Map.of("input_tokens", 12, "total_tokens", 16));
    accounting.finish(tenant, known, false, true);
    accounting.finish(tenant, known, true, false);
    var row = db.one("SELECT * FROM generation_usage WHERE request_id=?", known);
    assertThat(row.get("request_state")).isEqualTo("CANCELLED");
    assertThat(Db.num(row, "total_tokens")).isEqualTo(16);
    assertThat(row.get("output_tokens")).isNull();
    accounting.started(tenant, unknown);
    accounting.finish(tenant, unknown, false, true);
    assertThat(db.one("SELECT * FROM generation_usage WHERE request_id=?", unknown))
        .containsEntry("usage_state", "UNKNOWN")
        .containsEntry("total_tokens", null);
    accounting.finish(tenant, unused, true, false);
    assertThat(db.one("SELECT * FROM generation_usage WHERE request_id=?", unused))
        .containsEntry("usage_state", "NOT_CALLED");
  }

  @Test
  void tenantAndCallStateFenceUsageWritesAndZeroRequiresActualReport() {
    String tenant = Db.id(), id = reservation(tenant);
    assertThatThrownBy(() -> accounting.started(Db.id(), id))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> accounting.reported(tenant, id, Map.of("total_tokens", 1)))
        .isInstanceOf(IllegalStateException.class);
    accounting.started(tenant, id);
    assertThatThrownBy(() -> accounting.reported(tenant, id, Map.of("total_tokens", "3")))
        .isInstanceOf(IllegalArgumentException.class);
    accounting.reported(tenant, id, Map.of("total_tokens", 0));
    accounting.reported(tenant, id, Map.of());
    assertThat(db.one("SELECT * FROM generation_usage WHERE request_id=?", id))
        .containsEntry("usage_state", "REPORTED");
    assertThat(
            Db.num(db.one("SELECT * FROM generation_usage WHERE request_id=?", id), "total_tokens"))
        .isZero();
  }
}
