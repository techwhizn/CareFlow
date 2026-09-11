package com.careflow.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ModelKeyRotationServiceTest {
  @Test
  void rotatesAllSecretRowsWithoutReturningPlaintext() throws Exception {
    String oldKey = Base64.getEncoder().encodeToString(new byte[32]);
    byte[] next = new byte[32];
    Arrays.fill(next, (byte) 7);
    String newKey = Base64.getEncoder().encodeToString(next);
    String encrypted = ModelKeyVault.encryptWithKey(oldKey, "tenant", "profile", "secret-value");
    Db db = mock(Db.class);
    when(db.list(startsWith("SELECT id,tenant_id,"), any(Object[].class)))
        .thenReturn(List.of(Map.of("id", "profile", "tenant_id", "tenant", "secret", encrypted)))
        .thenReturn(List.of())
        .thenReturn(List.of());
    when(db.exec(anyString(), any(Object[].class))).thenReturn(1);
    var result = new ModelKeyRotationService(db, newKey, oldKey).rotate();
    assertThat(result).containsEntry("status", "COMPLETED").containsEntry("rotated", 1L);
    verify(db).exec(contains("api_key_ciphertext"), any(Object[].class));
    verify(db, never()).exec(contains("secret-value"), any(Object[].class));
  }
}
