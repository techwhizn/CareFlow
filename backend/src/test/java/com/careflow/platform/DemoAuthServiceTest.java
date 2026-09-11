package com.careflow.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DemoAuthServiceTest {
  @Test
  void disabledDemoLoginIsHidden() {
    DemoAuthService service = new DemoAuthService(mock(Db.class), mock(Identity.class), false);
    assertThat(service.enabled()).isFalse();
    assertThatThrownBy(service::login)
        .isInstanceOf(ApiException.class)
        .extracting("status")
        .isEqualTo(404);
  }

  @Test
  void enabledDemoLoginIssuesShortLivedCredential() {
    Db db = mock(Db.class);
    Identity identity = mock(Identity.class);
    when(db.one(
            "SELECT id,role FROM members WHERE tenant_id=? AND role='OWNER' "
                + "AND active=TRUE AND removed=FALSE ORDER BY id LIMIT 1",
            "00000000-0000-0000-0000-000000000001"))
        .thenReturn(java.util.Map.of("id", "member"));
    when(identity.credential(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn("demo-token");

    Map<String, Object> result = new DemoAuthService(db, identity, true).login();
    assertThat(result)
        .containsEntry("token", "demo-token")
        .containsEntry("expires_in_hours", 8);
  }
}
