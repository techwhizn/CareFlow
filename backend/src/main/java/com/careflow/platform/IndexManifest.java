package com.careflow.platform;

import static com.careflow.platform.Db.*;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

final class IndexManifest {
  private IndexManifest() {}

  static String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static List<WorkerProtocolV1.IndexEntry> entries(List<Map<String, Object>> chunks) {
    return chunks.stream()
        .map(row -> new WorkerProtocolV1.IndexEntry(str(row, "id"), hash(str(row, "content"))))
        .toList();
  }

  static String digest(List<WorkerProtocolV1.IndexEntry> entries) {
    StringBuilder canonical = new StringBuilder();
    entries.stream()
        .sorted(Comparator.comparing(WorkerProtocolV1.IndexEntry::id))
        .forEach(
            row -> canonical.append(row.id()).append(':').append(row.content_hash()).append('\n'));
    return hash(canonical.toString());
  }
}
