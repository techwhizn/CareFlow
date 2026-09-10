package com.careflow.platform;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class UploadValidationTest {
  @Test
  void rejectsDisguisedBinaryAndInvalidText() {
    for (String name : new String[] {"fake.pdf", "fake.png", "fake.jpg", "fake.docx", "fake.xlsx"})
      assertThatThrownBy(
              () ->
                  UploadValidation.validate(
                      new MockMultipartFile(
                          "file", name, "application/octet-stream", "plain text".getBytes())))
          .isInstanceOf(ApiException.class);
    for (byte[] content : new byte[][] {{(byte) 0xff}, {'a', 0, 'b'}, {}})
      assertThatThrownBy(
              () ->
                  UploadValidation.validate(
                      new MockMultipartFile("file", "fake.txt", "text/plain", content)))
          .isInstanceOf(ApiException.class);
  }

  @Test
  void acceptsUtf8TextAndComputesContentDigest() throws Exception {
    var first =
        UploadValidation.validate(
            new MockMultipartFile(
                "file",
                "notes.md",
                "text/plain",
                "# 中文资料".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    var second =
        UploadValidation.validate(
            new MockMultipartFile("file", "other.txt", "text/plain", first.bytes()));
    assertThat(first.digest()).isEqualTo(second.digest()).hasSize(64);
  }
}
