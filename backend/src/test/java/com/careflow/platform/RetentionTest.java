package com.careflow.platform;

import static com.careflow.platform.Db.*;
import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class RetentionTest extends ContentTestSupport {
  @Autowired RetentionService retention;

  String record(int age) {
    String id = id();
    db.exec(
        "INSERT INTO query_records(id,tenant_id,subject_id,subject_kind,question,knowledge_base_ids,query_options,evidence_status,created_at) VALUES(?,?,?,'MEMBER','Synthetic debug body','[]','{\"filter\":\"sensitive\"}','NO_MATCH',TIMESTAMPADD(DAY,?,CURRENT_TIMESTAMP))",
        id,
        tenant,
        actor.subject(),
        -age);
    return id;
  }

  @Test
  void retentionScrubsOnlyExpiredDebugBodiesAndAuditMetadata() {
    String old = record(31), fresh = record(1);
    auth.audit(actor, "TEST", old, "synthetic audit");
    db.exec(
        "UPDATE audit_events SET created_at=TIMESTAMPADD(DAY,-181,CURRENT_TIMESTAMP) WHERE tenant_id=?",
        tenant);
    retention.sweepTenant(tenant);
    assertThat(db.one("SELECT question,body_state FROM query_records WHERE id=?", old))
        .containsEntry("question", "")
        .containsEntry("body_state", "EXPIRED");
    assertThat(
            Db.str(
                db.one("SELECT query_options FROM query_records WHERE id=?", old), "query_options"))
        .isEqualTo("{}");
    assertThat(str(db.one("SELECT question FROM query_records WHERE id=?", fresh), "question"))
        .isEqualTo("Synthetic debug body");
    assertThat(db.list("SELECT id FROM audit_events WHERE tenant_id=?", tenant)).isEmpty();
    retention.sweepTenant(tenant);
    assertThat(db.list("SELECT id FROM query_records WHERE tenant_id=?", tenant)).hasSize(2);
  }

  @Test
  void disablingCollectionUsesRevisionAndScrubsExistingBodies() {
    String fresh = record(0);
    retention.update(actor, new RetentionService.Input(false, 30, 180, 0));
    assertThat(retention.collect(tenant)).isFalse();
    assertThatThrownBy(() -> retention.update(actor, new RetentionService.Input(true, 30, 180, 0)))
        .isInstanceOf(ApiException.class);
    retention.sweepTenant(tenant);
    assertThat(db.one("SELECT question,body_state FROM query_records WHERE id=?", fresh))
        .containsEntry("question", "")
        .containsEntry("body_state", "DISABLED");
    assertThat(db.list("SELECT id FROM audit_events WHERE tenant_id=?", tenant)).hasSize(1);
  }

  @Test
  void retentionRemovesOnlyTerminalIntegrationDeliveries() {
    String delivered = id(), pending = id();
    db.exec(
        "INSERT INTO integration_deliveries(id,tenant_id,endpoint_id,event_type,payload,status,created_at) VALUES(?,?,?,'TEST','{}','DELIVERED',TIMESTAMPADD(DAY,-181,CURRENT_TIMESTAMP))",
        delivered,
        tenant,
        id());
    db.exec(
        "INSERT INTO integration_deliveries(id,tenant_id,endpoint_id,event_type,payload,status) VALUES(?,?,?,'TEST','{}','PENDING')",
        pending,
        tenant,
        id());
    retention.sweepTenant(tenant);
    assertThat(db.list("SELECT id FROM integration_deliveries WHERE tenant_id=?", tenant))
        .extracting(row -> str(row, "id"))
        .containsExactly(pending);
  }
}
