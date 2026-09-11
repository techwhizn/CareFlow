package com.careflow.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IntegrationDeliveryServiceTest {
  @Test
  void webhookSignatureIsDeterministicAndChangesWithPayload() {
    String first = IntegrationDeliveryService.sign("test-secret", "{\"id\":1}");
    assertThat(first).isEqualTo("1f5f637644edd1b7551c96e61fcee6b5f49aeee77e99eacc1005acbdb6d11a46");
    assertThat(IntegrationDeliveryService.sign("test-secret", "{\"id\":2}")).isNotEqualTo(first);
  }
}
