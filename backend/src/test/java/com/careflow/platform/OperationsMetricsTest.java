package com.careflow.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.careflow.platform.Identity.Actor;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class OperationsMetricsTest extends ContentTestSupport {
  @Autowired OperationsMetricsService metrics;

  @Test
  void tenantGaugesAndAlertsExcludeOtherTenantsAndContent() throws Exception {
    String own = reservedRequest(actor);
    db.exec(
        "UPDATE usage_events SET expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE id=?",
        own);
    var ownResult = metrics.metrics(actor);
    assertThat(ownResult.toString())
        .contains("careflow_queries_expired=1")
        .doesNotContain(own)
        .doesNotContain("Synthetic");
    assertThat((List<?>) ownResult.get("alerts")).hasSize(1);
    var other = new Actor(Db.id(), Db.id(), "MEMBER", "OPS");
    assertThat(metrics.metrics(other).toString()).contains("careflow_queries_expired=0");
    assertThatThrownBy(() -> metrics.metrics(new Actor(tenant, actor.subject(), "MEMBER", "USER")))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () -> metrics.metrics(new Actor(tenant, actor.subject(), "APP", "APPLICATION")))
        .isInstanceOf(ApiException.class);
    mvc.perform(get("/api/v1/operations/prometheus").header("Authorization", token))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.containsString(
                        "# TYPE careflow_queries_reserved gauge")));
  }
}
