package com.careflow.platform;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class ModelKeyVaultTest {
  @Test
  void ciphertextUsesRandomNonceAndBindsToTenantAndProfile() throws Exception {
    byte[] key = new byte[32];
    var vault = new ModelKeyVault(Base64.getEncoder().encodeToString(key));
    String a = vault.encrypt("tenant", "profile", "secret"),
        b = vault.encrypt("tenant", "profile", "secret");
    assertThat(a).isNotEqualTo(b);
    String[] pieces = a.split(":");
    var cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        new SecretKeySpec(key, "AES"),
        new GCMParameterSpec(128, Base64.getDecoder().decode(pieces[1])));
    cipher.updateAAD("tenant:profile".getBytes(StandardCharsets.UTF_8));
    assertThat(
            new String(
                cipher.doFinal(Base64.getDecoder().decode(pieces[2])), StandardCharsets.UTF_8))
        .isEqualTo("secret");
    cipher.init(
        Cipher.DECRYPT_MODE,
        new SecretKeySpec(key, "AES"),
        new GCMParameterSpec(128, Base64.getDecoder().decode(pieces[1])));
    cipher.updateAAD("other:profile".getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> cipher.doFinal(Base64.getDecoder().decode(pieces[2])))
        .isInstanceOf(javax.crypto.AEADBadTagException.class);
  }

  @Test
  void invalidDeploymentKeyFailsClosed() {
    assertThatThrownBy(() -> new ModelKeyVault("").encrypt("t", "p", "secret"))
        .isInstanceOf(ApiException.class);
    assertThat(new ModelKeyVault("").encrypt("t", "p", "")).isEmpty();
  }
}
