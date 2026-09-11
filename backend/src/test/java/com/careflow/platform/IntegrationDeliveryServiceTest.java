package com.careflow.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.careflow.platform.Identity.Actor;
import org.junit.jupiter.api.Test;

class IntegrationDeliveryServiceTest {
  @Test
  void webhookSignatureIsDeterministicAndChangesWithPayload() {
    String first = IntegrationDeliveryService.sign("test-secret", "{\"id\":1}");
    assertThat(first).isEqualTo("1f5f637644edd1b7551c96e61fcee6b5f49aeee77e99eacc1005acbdb6d11a46");
    assertThat(IntegrationDeliveryService.sign("test-secret", "{\"id\":2}")).isNotEqualTo(first);
  }

  @Test
  void retryOnlyRequeuesTerminalFailure() {
    Db db = mock(Db.class);
    Identity identity = mock(Identity.class);
    ModelKeyVault vault = mock(ModelKeyVault.class);
    IntegrationService service = new IntegrationService(db, identity, vault);
    doReturn(1).when(db).exec(anyString(), any(Object[].class));
    service.retry(new Actor("tenant", "member", "MEMBER", "ADMIN"), "endpoint", "delivery");
    verify(db).exec(contains("status='PENDING'"), any(Object[].class));
    verify(identity).audit(any(), eq("INTEGRATION_DELIVERY_RETRY"), eq("delivery"), anyString());
  }
}
