package com.careflow.platform;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SsoServiceTest {
  @Test
  void missingProviderConfigurationFailsExplicitly() {
    SsoService service =
        new SsoService(mock(Db.class), mock(Identity.class), new ObjectMapper(), "", "", "");
    assertThatThrownBy(() -> service.exchange("external-token"))
        .isInstanceOf(ApiException.class)
        .hasFieldOrPropertyWithValue("status", 503)
        .hasFieldOrPropertyWithValue("code", "SSO_UNAVAILABLE");
  }
}
