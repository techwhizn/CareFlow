package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only quality diagnostics over one authorized content-version snapshot. */
@Service
public class ChunkQualityService {
  public record Issue(
      String chunk_id, long ordinal, int page, List<String> codes, String location) {}

  public record Report(
      long revision,
      long chunks,
      long enabled_chunks,
      long tokens,
      Map<String, Long> distribution,
      Map<String, Long> warnings,
      long issue_count,
      List<Issue> issues,
      int page,
      boolean has_more) {}

  private final Db db;
  private final Identity auth;
  private final KnowledgeConfigurationService configurations;
  private final ObjectMapper json;

  public ChunkQualityService(
      Db db, Identity auth, KnowledgeConfigurationService configurations, ObjectMapper json) {
    this.db = db;
    this.auth = auth;
    this.configurations = configurations;
    this.json = json;
  }

  @Transactional(readOnly = true)
  public Report inspect(Actor actor, String version, int page) {
    if (page < 0 || page > 10000) throw new IllegalArgumentException();
    var source = auth.version(actor, version, "read");
    String configuration = str(source, "configuration_id");
    int maximum =
        configuration.isBlank()
            ? 600
            : configurations.definition(actor.tenant(), configuration).chunking().maximum();
    Map<String, Integer> occurrences = new HashMap<>();
    scan(
        actor.tenant(),
        version,
        row -> occurrences.merge(digest(str(row, "content")), 1, Integer::sum));
    long[] totals = new long[4];
    Map<String, Long> distribution = new LinkedHashMap<>(), warnings = new TreeMap<>();
    for (String key : List.of("0", "1-19", "20-199", "200-399", "400-600", "601+"))
      distribution.put(key, 0L);
    List<Issue> issues = new ArrayList<>();
    scan(
        actor.tenant(),
        version,
        row -> {
          long index = totals[0]++, count = num(row, "token_count");
          if (bool(row, "enabled")) {
            totals[1]++;
            totals[2] += count;
          }
          String bucket =
              count == 0
                  ? "0"
                  : count < 20
                      ? "1-19"
                      : count < 200
                          ? "20-199"
                          : count < 400 ? "200-399" : count <= 600 ? "400-600" : "601+";
          distribution.merge(bucket, 1L, Long::sum);
          var codes = classify(row, maximum, occurrences.get(digest(str(row, "content"))) > 1);
          if (codes.isEmpty()) return;
          codes.forEach(code -> warnings.merge(code, 1L, Long::sum));
          long issue = totals[3]++;
          if (issue >= (long) page * 100 && issues.size() < 100)
            issues.add(
                new Issue(
                    str(row, "id"),
                    num(row, "ordinal_no"),
                    (int) (index / 100),
                    codes,
                    str(row, "location")));
        });
    return new Report(
        num(source, "revision"),
        totals[0],
        totals[1],
        totals[2],
        distribution,
        warnings,
        totals[3],
        issues,
        page,
        totals[3] > (long) (page + 1) * 100);
  }

  private void scan(String tenant, String version, Consumer<Map<String, Object>> consume) {
    long after = -1;
    while (true) {
      var rows =
          db.list(
              "SELECT id,ordinal_no,content,token_count,enabled,location FROM chunks WHERE tenant_id=? AND version_id=? AND ordinal_no>? ORDER BY ordinal_no LIMIT 250",
              tenant,
              version,
              after);
      if (rows.isEmpty()) return;
      rows.forEach(consume);
      after = num(rows.getLast(), "ordinal_no");
    }
  }

  List<String> classify(Map<String, Object> row, int maximum, boolean duplicate) {
    Set<String> codes = new LinkedHashSet<>();
    String content = str(row, "content");
    long count = num(row, "token_count");
    if (content.isBlank() || count == 0) codes.add("EMPTY_CONTENT");
    else if (count < 20) codes.add("TOO_SHORT");
    if (count > maximum) codes.add("TOO_LONG");
    if (duplicate) codes.add("DUPLICATE");
    JsonNode location;
    try {
      location = json.readTree(str(row, "location"));
      if (location == null || !location.isObject()) throw new IllegalArgumentException();
    } catch (Exception error) {
      codes.add("INVALID_LOCATION");
      return List.copyOf(codes);
    }
    var incoming = location.path("quality_codes");
    if (incoming.isArray())
      for (var code : incoming)
        if (Set.of(
                "EMPTY_CONTENT",
                "TABLE_HEADER_MISSING",
                "TABLE_HEADER_DUPLICATE",
                "TABLE_ROW_BROKEN")
            .contains(code.asText())) codes.add(code.asText());
    String warning = location.path("warning").asText("");
    if (warning.contains("OCR")) codes.add("OCR_REVIEW");
    if (content.indexOf('\uFFFD') >= 0
        || content
            .codePoints()
            .anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
      codes.add("TEXT_ANOMALY");
    if (location.path("type").asText().equals("table")) {
      if (warning.contains("合并") || warning.contains("多行表头")) codes.add("TABLE_STRUCTURE_REVIEW");
      if (warning.contains("列数")) codes.add("TABLE_ROW_BROKEN");
      if (location.path("block_start").asInt() > 0
          || (location.has("block_length")
              && location.path("block_end").asInt() < location.path("block_length").asInt()))
        codes.add("TABLE_ROW_SPLIT");
      if (!location.has("headers")) codes.add("TABLE_HEADER_MISSING");
    }
    if (!warning.isBlank() && !warning.equals("切片较短，请检查上下文是否完整")) codes.add("SOURCE_REVIEW");
    return List.copyOf(codes);
  }

  private static String digest(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(text.strip().replaceAll("\\s+", " ").getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
