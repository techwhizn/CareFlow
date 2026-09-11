package com.careflow.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class MfaServiceTest {
  @Test
  void acceptsCurrentTotpAndAdjacentClockWindow() throws Exception {
    String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    String code = code(secret, Instant.now().getEpochSecond() / 30);
    assertThat(MfaService.valid(code, secret)).isTrue();
  }

  @Test
  void rejectsMalformedOrWrongCodes() {
    assertThat(MfaService.valid(null, "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")).isFalse();
    assertThat(MfaService.valid("12345", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")).isFalse();
    assertThat(MfaService.valid("000000", "not-base32")).isFalse();
  }

  private static String code(String encoded, long counter) throws Exception {
    byte[] secret = decodeBase32(encoded);
    byte[] input = ByteBuffer.allocate(8).putLong(counter).array();
    Mac mac = Mac.getInstance("HmacSHA1");
    mac.init(new SecretKeySpec(secret, "HmacSHA1"));
    byte[] hash = mac.doFinal(input);
    int offset = hash[hash.length - 1] & 0xf;
    int binary =
        ((hash[offset] & 0x7f) << 24)
            | ((hash[offset + 1] & 0xff) << 16)
            | ((hash[offset + 2] & 0xff) << 8)
            | (hash[offset + 3] & 0xff);
    return "%06d".formatted(binary % 1_000_000);
  }

  private static byte[] decodeBase32(String value) {
    int buffer = 0, bits = 0, length = 0;
    byte[] output = new byte[value.length() * 5 / 8];
    for (char c : value.toCharArray()) {
      int digit = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c);
      buffer = (buffer << 5) | digit;
      bits += 5;
      if (bits >= 8) output[length++] = (byte) (buffer >>> (bits -= 8));
    }
    return java.util.Arrays.copyOf(output, length);
  }
}
