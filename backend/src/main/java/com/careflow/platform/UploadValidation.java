package com.careflow.platform;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.web.multipart.MultipartFile;

/** Early bounded signature checks; complete parsing remains the Worker's responsibility. */
public final class UploadValidation {
  private UploadValidation() {}

  public record FileData(String name, String digest, byte[] bytes) {}

  public static FileData validate(MultipartFile file) throws Exception {
    if (file.isEmpty() || file.getSize() > 50L * 1024 * 1024)
      throw new ApiException(413, "FILE_TOO_LARGE", "文件为空或超过50MiB");
    String name = Objects.toString(file.getOriginalFilename(), "file").replaceAll(".*[/\\\\]", "");
    if (name.length() > 250 || name.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException();
    String ext =
        name.contains(".")
            ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
            : "";
    byte[] data = file.getBytes();
    boolean valid =
        switch (ext) {
          case "pdf" -> prefix(data, new byte[] {37, 80, 68, 70, 45});
          case "png" -> prefix(data, new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
          case "jpg", "jpeg" -> prefix(data, new byte[] {(byte) 255, (byte) 216, (byte) 255});
          case "docx", "xlsx" -> prefix(data, new byte[] {80, 75, 3, 4});
          case "txt", "md", "csv" -> text(data);
          default -> throw new ApiException(400, "UNSUPPORTED_FILE", "不支持的文件类型");
        };
    if (!valid) throw new ApiException(400, "FILE_TYPE_MISMATCH", "实际文件内容与扩展名不符或文本不是有效UTF-8");
    return new FileData(
        name, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)), data);
  }

  private static boolean prefix(byte[] data, byte[] expected) {
    return data.length >= expected.length
        && Arrays.equals(Arrays.copyOf(data, expected.length), expected);
  }

  private static boolean text(byte[] data) {
    try {
      String value =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(data))
              .toString();
      return value.indexOf('\0') < 0;
    } catch (CharacterCodingException e) {
      return false;
    }
  }
}
