package com.careflow.platform;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The deployment key is kept outside the database and never returned to clients. */
@Component
public class ModelKeyVault {
  private final String configured;

  public ModelKeyVault(@Value("${careflow.model-encryption-key:}") String configured) {
    this.configured = configured;
  }

  public String encrypt(String tenant, String profile, String value) {
    if (value == null || value.isEmpty()) return "";
    try {
      return encryptWithKey(configured, tenant, profile, value);
    } catch (Exception e) {
      throw new ApiException(503, "MODEL_KEY_STORAGE_UNAVAILABLE", "模型密钥存储未正确配置");
    }
  }

  public String decrypt(String tenant, String profile, String encrypted) {
    if (encrypted == null || encrypted.isEmpty()) return "";
    try {
      String[] parts = encrypted.split(":", -1);
      if (parts.length != 3 || !parts[0].equals("v1")) throw new IllegalArgumentException();
      return decryptWithKey(configured, tenant, profile, encrypted);
    } catch (Exception e) {
      throw new ApiException(503, "MODEL_KEY_STORAGE_UNAVAILABLE", "模型密钥无法解密，请检查部署密钥与快照");
    }
  }

  public String reencrypt(String oldKey, String tenant, String profile, String encrypted) {
    if (encrypted == null || encrypted.isEmpty()) return "";
    try {
      return encryptWithKey(
          configured, tenant, profile, decryptWithKey(oldKey, tenant, profile, encrypted));
    } catch (Exception e) {
      throw new ApiException(503, "MODEL_KEY_STORAGE_UNAVAILABLE", "模型密钥无法轮换，请检查部署密钥与快照");
    }
  }

  static String encryptWithKey(String encodedKey, String tenant, String profile, String value)
      throws Exception {
    byte[] key = Base64.getDecoder().decode(encodedKey);
    if (key.length != 32) throw new IllegalArgumentException();
    byte[] nonce = new byte[12];
    new SecureRandom().nextBytes(nonce);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
    cipher.updateAAD((tenant + ":" + profile).getBytes(StandardCharsets.UTF_8));
    return "v1:"
        + Base64.getEncoder().encodeToString(nonce)
        + ":"
        + Base64.getEncoder()
            .encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
  }

  static String decryptWithKey(String encodedKey, String tenant, String profile, String encrypted)
      throws Exception {
    String[] parts = encrypted.split(":", -1);
    if (parts.length != 3 || !parts[0].equals("v1")) throw new IllegalArgumentException();
    byte[] key = Base64.getDecoder().decode(encodedKey);
    byte[] nonce = Base64.getDecoder().decode(parts[1]);
    if (key.length != 32 || nonce.length != 12) throw new IllegalArgumentException();
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
    cipher.updateAAD((tenant + ":" + profile).getBytes(StandardCharsets.UTF_8));
    return new String(cipher.doFinal(Base64.getDecoder().decode(parts[2])), StandardCharsets.UTF_8);
  }
}
