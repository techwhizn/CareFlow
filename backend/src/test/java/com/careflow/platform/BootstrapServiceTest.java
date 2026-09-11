package com.careflow.platform;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

class BootstrapServiceTest {
  @Test
  void reportsAlreadyInitializedInsteadOfGenericDuplicateConflict() {
    Db db = mock(Db.class);
    when(db.list("SELECT id FROM tenants WHERE id=?", "00000000-0000-0000-0000-000000000001"))
        .thenReturn(List.of(java.util.Map.of("id", "00000000-0000-0000-0000-000000000001")));

    BootstrapService service =
        new BootstrapService(
            db, mock(Identity.class), "bootstrap-testing-secret-at-least-32-chars");

    assertThatThrownBy(
            () ->
                service.bootstrap(
                    "bootstrap-testing-secret-at-least-32-chars",
                    new BootstrapService.Named("Acme", "")))
        .isInstanceOf(ApiException.class)
        .extracting("status", "code", "message")
        .containsExactly(409, "ALREADY_INITIALIZED", "企业已初始化，请使用所有者或管理员访问凭证登录");
  }
}
