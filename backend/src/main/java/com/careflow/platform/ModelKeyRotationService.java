package com.careflow.platform;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Re-encrypts every platform secret in one explicit maintenance transaction. */
@Service
public class ModelKeyRotationService {
  private final Db db;
  private final String currentKey;
  private final String previousKey;

  public ModelKeyRotationService(
      Db db,
      @Value("${careflow.model-encryption-key:}") String currentKey,
      @Value("${careflow.model-encryption-key-old:}") String previousKey) {
    this.db = db;
    this.currentKey = currentKey;
    this.previousKey = previousKey;
  }

  @Transactional
  public Map<String, Object> rotate() {
    if (previousKey.isBlank() || currentKey.isBlank() || previousKey.equals(currentKey))
      throw new ApiException(503, "MODEL_KEY_ROTATION_UNAVAILABLE", "未配置不同的旧密钥和当前密钥");
    long count = 0;
    count += rotateTable("model_profiles", "api_key_ciphertext");
    count += rotateTable("members", "mfa_secret");
    count += rotateTable("integration_endpoints", "secret_ciphertext");
    return Map.of("rotated", count, "status", "COMPLETED");
  }

  private long rotateTable(String table, String column) {
    long count = 0;
    for (var row : db.list("SELECT id,tenant_id," + column + " AS secret FROM " + table)) {
      String encrypted = Db.str(row, "secret");
      if (encrypted.isBlank()) continue;
      String replacement;
      try {
        replacement =
            ModelKeyVault.encryptWithKey(
                currentKey,
                Db.str(row, "tenant_id"),
                Db.str(row, "id"),
                ModelKeyVault.decryptWithKey(
                    previousKey, Db.str(row, "tenant_id"), Db.str(row, "id"), encrypted));
      } catch (Exception e) {
        throw new ApiException(503, "MODEL_KEY_ROTATION_FAILED", "存在无法用旧密钥解密的凭证");
      }
      count +=
          db.exec(
              "UPDATE " + table + " SET " + column + "=? WHERE tenant_id=? AND id=?",
              replacement,
              Db.str(row, "tenant_id"),
              Db.str(row, "id"));
    }
    return count;
  }
}
