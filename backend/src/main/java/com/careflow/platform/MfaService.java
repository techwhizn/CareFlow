package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MfaService {
  public record Code(@NotBlank @Size(min = 6, max = 6) String code) {}

  public record Status(boolean enabled, boolean enrolled, long revision) {}

  private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
  private final Db db;
  private final Identity auth;
  private final ModelKeyVault vault;
  private final SecureRandom random = new SecureRandom();

  public MfaService(Db db, Identity auth, ModelKeyVault vault) {
    this.db = db;
    this.auth = auth;
    this.vault = vault;
  }

  public Status status(Actor actor) {
    var row = member(actor);
    return new Status(
        Db.bool(row, "mfa_enabled"),
        !Db.str(row, "mfa_secret").isBlank(),
        Db.num(row, "mfa_revision"));
  }

  @Transactional
  public Map<String, Object> enroll(Actor actor) {
    var row = member(actor);
    String secret = secret();
    String encrypted = vault.encrypt(actor.tenant(), actor.subject(), secret);
    long revision = Db.num(row, "mfa_revision") + 1;
    if (db.exec(
            "UPDATE members SET mfa_secret=?,mfa_enabled=FALSE,mfa_revision=? WHERE tenant_id=? AND id=? AND mfa_revision=?",
            encrypted,
            revision,
            actor.tenant(),
            actor.subject(),
            Db.num(row, "mfa_revision"))
        != 1) throw new ApiException(409, "MFA_REVISION_CONFLICT", "MFA 状态已变化，请重新开始绑定");
    auth.audit(actor, "MFA_ENROLL", actor.subject(), "enabled=false");
    String label =
        "CareFlow:" + Db.str(db.one("SELECT name FROM tenants WHERE id=?", actor.tenant()), "name");
    String uri =
        "otpauth://totp/"
            + URLEncoder.encode(label, StandardCharsets.UTF_8)
            + "?secret="
            + secret
            + "&issuer=CareFlow";
    return Map.of("secret", secret, "otpauth_uri", uri, "revision", revision);
  }

  @Transactional
  public Status enable(Actor actor, Code input) {
    var row = member(actor);
    requireCode(row, input.code());
    long revision = Db.num(row, "mfa_revision");
    if (db.exec(
            "UPDATE members SET mfa_enabled=TRUE,mfa_revision=mfa_revision+1 WHERE tenant_id=? AND id=? AND mfa_revision=?",
            actor.tenant(),
            actor.subject(),
            revision)
        != 1) throw new ApiException(409, "MFA_REVISION_CONFLICT", "MFA 状态已变化，请重试");
    auth.audit(actor, "MFA_ENABLE", actor.subject(), "");
    return status(actor);
  }

  @Transactional
  public Status disable(Actor actor, Code input) {
    var row = member(actor);
    requireCode(row, input.code());
    if (db.exec(
            "UPDATE members SET mfa_enabled=FALSE,mfa_revision=mfa_revision+1 WHERE tenant_id=? AND id=? AND mfa_revision=?",
            actor.tenant(),
            actor.subject(),
            Db.num(row, "mfa_revision"))
        != 1) throw new ApiException(409, "MFA_REVISION_CONFLICT", "MFA 状态已变化，请重试");
    auth.audit(actor, "MFA_DISABLE", actor.subject(), "");
    return status(actor);
  }

  public boolean required(Actor actor) {
    return !actor.app() && !actor.role().equals("OPS") && Db.bool(member(actor), "mfa_enabled");
  }

  public void requireCode(Actor actor, String code) {
    if (required(actor)) requireCode(member(actor), code);
  }

  private void requireCode(Map<String, Object> row, String code) {
    if (!valid(code, secret(row))) throw new ApiException(401, "MFA_REQUIRED", "请提供有效的一次性验证码");
  }

  private Map<String, Object> member(Actor actor) {
    if (actor.app() || actor.role().equals("OPS")) throw ApiException.hidden();
    return db.one(
        "SELECT * FROM members WHERE tenant_id=? AND id=? AND active=TRUE AND removed=FALSE",
        actor.tenant(),
        actor.subject());
  }

  private String secret(Map<String, Object> row) {
    String encrypted = Db.str(row, "mfa_secret");
    if (encrypted.isBlank()) throw new ApiException(400, "MFA_NOT_ENROLLED", "请先绑定 MFA");
    return vault.decrypt(Db.str(row, "tenant_id"), Db.str(row, "id"), encrypted);
  }

  private String secret() {
    byte[] bytes = new byte[20];
    random.nextBytes(bytes);
    StringBuilder result = new StringBuilder(32);
    int buffer = 0, bits = 0;
    for (byte value : bytes) {
      buffer = (buffer << 8) | (value & 0xff);
      bits += 8;
      while (bits >= 5) {
        result.append(ALPHABET[(buffer >>> (bits - 5)) & 31]);
        bits -= 5;
      }
    }
    if (bits > 0) result.append(ALPHABET[(buffer << (5 - bits)) & 31]);
    return result.toString();
  }

  static boolean valid(String value, String encodedSecret) {
    if (value == null || !value.matches("[0-9]{6}") || encodedSecret == null) return false;
    byte[] secret;
    try {
      secret = decode(encodedSecret);
    } catch (IllegalArgumentException e) {
      return false;
    }
    long counter = Instant.now().getEpochSecond() / 30;
    for (long offset = -1; offset <= 1; offset++)
      if (constantTime(value, code(secret, counter + offset))) return true;
    return false;
  }

  private static boolean constantTime(String left, String right) {
    return java.security.MessageDigest.isEqual(
        left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
  }

  private static String code(byte[] secret, long counter) {
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(secret, "HmacSHA1"));
      byte[] input = new byte[8];
      for (int i = 7; i >= 0; i--) {
        input[i] = (byte) counter;
        counter >>>= 8;
      }
      byte[] hash = mac.doFinal(input);
      int offset = hash[hash.length - 1] & 0x0f;
      int binary =
          ((hash[offset] & 0x7f) << 24)
              | ((hash[offset + 1] & 0xff) << 16)
              | ((hash[offset + 2] & 0xff) << 8)
              | (hash[offset + 3] & 0xff);
      return "%06d".formatted(binary % 1_000_000);
    } catch (Exception e) {
      throw new IllegalStateException("TOTP unavailable", e);
    }
  }

  private static byte[] decode(String value) {
    String normalized = value.replace("=", "").toUpperCase();
    java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
    int buffer = 0, bits = 0;
    for (char c : normalized.toCharArray()) {
      int index = new String(ALPHABET).indexOf(c);
      if (index < 0) throw new IllegalArgumentException();
      buffer = (buffer << 5) | index;
      bits += 5;
      if (bits >= 8) {
        output.write((buffer >>> (bits - 8)) & 0xff);
        bits -= 8;
      }
    }
    return output.toByteArray();
  }
}
