package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import java.util.*;
import org.springframework.stereotype.Service;

/** Expands only authoritative, same-version content after recall and reranking. */
@Service
public class EvidenceContextService {
  private final Db db;
  private final Identity auth;
  private final WorkerClient worker;

  public EvidenceContextService(Db db, Identity auth, WorkerClient worker) {
    this.db = db;
    this.auth = auth;
    this.worker = worker;
  }

  public Map<String, Map<String, Object>> prepare(
      Actor actor, List<Map<String, Object>> candidates, Runnable revalidate) {
    var expanded = new LinkedHashMap<String, Map<String, Object>>();
    for (var candidate : candidates) {
      auth.version(actor, str(candidate, "version_id"), "read");
      expanded.put(str(candidate, "id"), expand(actor.tenant(), candidate));
    }
    if (expanded.isEmpty()) return expanded;
    revalidate.run();
    var request =
        new WorkerProtocolV1.ContextTokensRequest(
            expanded.values().stream()
                .map(row -> new WorkerProtocolV1.Candidate(str(row, "id"), str(row, "content")))
                .toList());
    var result = worker.call("/internal/v1/context/tokens", request);
    if (!"cl100k_base".equals(result.get("tokenizer"))
        || !(result.get("counts") instanceof List<?> counts)
        || counts.size() != expanded.size())
      throw new ApiException(503, "CONTEXT_TOKENIZER_UNAVAILABLE", "证据Token计数不可用");
    var seen = new HashSet<String>();
    for (Object raw : counts) {
      if (!(raw instanceof Map<?, ?> count)
          || !(count.get("id") instanceof String id)
          || !expanded.containsKey(id)
          || !seen.add(id)
          || !(count.get("token_count") instanceof Number number)
          || number.longValue() < 1
          || number.longValue() > 100000
          || number.doubleValue() != number.longValue())
        throw new ApiException(503, "CONTEXT_TOKENIZER_UNAVAILABLE", "证据Token计数不可用");
      expanded.get(id).put("token_count", number.longValue());
    }
    return expanded;
  }

  private Map<String, Object> expand(String tenant, Map<String, Object> candidate) {
    String version = str(candidate, "version_id"), context = str(candidate, "context_id");
    var output = new LinkedHashMap<>(candidate);
    output.put("matched_content", str(candidate, "content"));
    output.put("matched_token_count", num(candidate, "token_count"));
    output.put("context_kind", "CHUNK");
    output.put("covered_chunk_ids", List.of(str(candidate, "id")));
    if (!context.isBlank()) {
      var parents =
          db.list(
              "SELECT content,kind,location FROM chunk_contexts WHERE tenant_id=? AND version_id=? AND id=?",
              tenant,
              version,
              context);
      var children =
          db.list(
              "SELECT id,enabled FROM chunks WHERE tenant_id=? AND version_id=? AND context_id=? ORDER BY ordinal_no LIMIT 51",
              tenant,
              version,
              context);
      if (!parents.isEmpty()
          && !children.isEmpty()
          && children.size() <= 50
          && children.stream().allMatch(row -> bool(row, "enabled"))) {
        String text = str(parents.getFirst(), "content");
        if (!text.isBlank() && text.length() <= 10000) {
          output.put("content", text);
          output.put("context_kind", str(parents.getFirst(), "kind"));
          output.put("context_location", str(parents.getFirst(), "location"));
          output.put("covered_chunk_ids", children.stream().map(row -> str(row, "id")).toList());
          return output;
        }
      }
      // A disabled child excludes the parent; never reintroduce its text through expansion.
      return output;
    }
    long ordinal = num(candidate, "ordinal_no");
    var neighbors =
        db.list(
            "SELECT id,content,ordinal_no,location FROM chunks WHERE tenant_id=? AND version_id=? AND enabled=TRUE AND context_id IS NULL AND ordinal_no>=? AND ordinal_no<=? ORDER BY ordinal_no",
            tenant,
            version,
            Math.max(0, ordinal - 1),
            ordinal + 1);
    if (neighbors.size() > 1) {
      String combined = "";
      for (var row : neighbors) combined = join(combined, str(row, "content"));
      if (combined.length() <= 10000) {
        output.put("content", combined);
        output.put("context_kind", "NEIGHBORS");
        output.put(
            "context_locations",
            neighbors.stream()
                .map(row -> Map.of("chunk_id", str(row, "id"), "location", str(row, "location")))
                .toList());
        output.put("covered_chunk_ids", neighbors.stream().map(row -> str(row, "id")).toList());
      }
    }
    return output;
  }

  static String join(String left, String right) {
    if (left.isEmpty()) return right;
    for (int length = Math.min(2000, Math.min(left.length(), right.length()));
        length >= 20;
        length--)
      if (left.regionMatches(left.length() - length, right, 0, length))
        return left + right.substring(length);
    return left + "\n\n" + right;
  }
}
