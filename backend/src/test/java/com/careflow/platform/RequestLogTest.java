package com.careflow.platform;

import static com.careflow.platform.Db.*;
import static org.assertj.core.api.Assertions.*;

import com.careflow.platform.Identity.Actor;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class RequestLogTest extends ContentTestSupport {
  @Autowired RetrievalService retrieval;
  @Autowired RequestLogService logs;
  @Autowired RetrievalAccounting accounting;

  @Test
  void recordsCancellationAndCostsWithoutExposingRequestKeyOrOtherApplications() {
    String app = id();
    db.exec(
        "INSERT INTO applications(id,tenant_id,name,description) VALUES(?,?,'Synthetic','')",
        app,
        tenant);
    String request = retrieval.reserve(actor, "private-idempotency-key", app);
    accounting.call(
        tenant,
        request,
        "RERANK",
        null,
        1,
        () -> Map.of("usage", Map.of("state", "REPORTED", "total_tokens", 17)));
    retrieval.settle(actor, request, false, true, "STREAM_INTERRUPTED");
    retrieval.settle(actor, request, true);
    Actor client = new Actor(tenant, app, "APP", "APP");
    String detail = logs.detail(client, app, UUID.fromString(request)).toString();
    assertThat(detail)
        .contains("CANCELLED", "RELEASED", "total_tokens=17")
        .doesNotContain("private-idempotency-key", "request_key", "lease_token");
    assertThatThrownBy(() -> logs.detail(client, id(), UUID.fromString(request)))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                logs.detail(
                    new Actor(id(), id(), "MEMBER", "OWNER"), app, UUID.fromString(request)))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> logs.list(client, null, null)).isInstanceOf(ApiException.class);
  }

  @Test
  @SuppressWarnings("unchecked")
  void cursorPaginationUsesStableOrderingWithoutDuplicates() {
    for (int i = 0; i < 102; i++)
      db.exec(
          "INSERT INTO usage_events(id,tenant_id,subject_id,request_key,resource_type,amount,state) VALUES(?,?,?,?,'QUERY',1,'SETTLED')",
          id(),
          tenant,
          actor.subject(),
          id());
    var first = (Map<String, Object>) logs.list(actor, null, null);
    var second =
        (Map<String, Object>) logs.list(actor, null, UUID.fromString(str(first, "next_cursor")));
    var rows = (List<Map<String, Object>>) first.get("items");
    var next = (List<Map<String, Object>>) second.get("items");
    assertThat(rows).hasSize(100);
    assertThat(next).hasSize(2);
    assertThat(rows.stream().map(r -> r.get("request_id")).toList())
        .doesNotContainAnyElementsOf(next.stream().map(r -> r.get("request_id")).toList());
    assertThat(str(second, "next_cursor")).isEmpty();
  }
}
