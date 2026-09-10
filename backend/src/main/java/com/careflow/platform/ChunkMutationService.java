package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ChunkMutationService {
  public record Ref(@NotBlank @Size(max = 36) String id, @Min(0) long revision) {}

  public record Operation(
      @NotNull @Min(0) Long revision,
      @NotBlank @Pattern(regexp = "SPLIT|MERGE|SET_ENABLED|TAGS") String action,
      @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid Ref> chunks,
      @Size(max = 19) List<@NotNull @Min(1) Integer> split_offsets,
      Boolean enabled,
      @Size(max = 20) List<@NotBlank @Size(max = 80) String> tags,
      @NotBlank @Size(max = 1000) String reason) {}

  private final Db db;
  private final Identity auth;
  private final ContentBudgetService budget;
  private final ObjectMapper json;
  private final TransactionTemplate tx;

  public ChunkMutationService(
      Db db,
      Identity auth,
      ContentBudgetService budget,
      ObjectMapper json,
      TransactionTemplate tx) {
    this.db = db;
    this.auth = auth;
    this.budget = budget;
    this.json = json;
    this.tx = tx;
  }

  Map<String, Object> editable(Actor actor, String version, long revision) {
    var row = auth.version(actor, version, "edit");
    var document = auth.document(actor, str(row, "document_id"), "edit");
    var kb = auth.kb(actor, str(document, "kb_id"), "edit");
    if (!str(document, "status").equals("ACTIVE") || !str(kb, "status").equals("ACTIVE"))
      throw ApiException.hidden();
    if (bool(row, "ever_published"))
      throw new ApiException(409, "IMMUTABLE_PUBLICATION", "已发布版本不可修改，请先复制草稿");
    if (!Set.of("PARSED", "READY", "FAILED").contains(str(row, "state")))
      throw new ApiException(409, "PROCESSING", "任务执行中不可编辑");
    if (num(row, "revision") != revision) throw ApiException.conflict();
    return row;
  }

  void reauthorize(Actor actor, String authorization) {
    auth.lock(actor);
    if (!actor.equals(auth.authenticate(authorization))) throw ApiException.hidden();
  }

  void advance(Actor actor, String version, long revision) {
    if (db.exec(
            "UPDATE document_versions SET revision=revision+1,state='PARSED' WHERE tenant_id=? AND id=? AND revision=? AND ever_published=FALSE",
            actor.tenant(),
            version,
            revision)
        != 1) throw ApiException.conflict();
  }

  void remember(
      Actor actor,
      String version,
      String action,
      Object previous,
      List<String> results,
      String reason) {
    db.exec(
        "INSERT INTO chunk_changes(id,tenant_id,version_id,action,previous_json,result_ids_json,actor_id,reason) VALUES(?,?,?,?,?,?,?,?)",
        id(),
        actor.tenant(),
        version,
        action,
        encode(previous),
        encode(results),
        actor.subject(),
        reason);
    auth.audit(actor, "CHUNK_" + action, version, reason);
  }

  String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException();
    }
  }

  public Object edit(
      Actor actor, String authorization, String id, DocumentDraftService.Edit input) {
    var row = db.one("SELECT * FROM chunks WHERE tenant_id=? AND id=?", actor.tenant(), id);
    var version = auth.version(actor, str(row, "version_id"), "edit");
    editable(actor, str(version, "id"), num(version, "revision"));
    if (!str(row, "context_id").isBlank())
      throw new ApiException(409, "CONTEXT_EDIT_REQUIRED", "请先解除分组关联，或修订整组FAQ");
    if (num(row, "revision") != input.revision()) throw ApiException.conflict();
    long count =
        budget
            .count(actor.tenant(), str(version, "configuration_id"), List.of(input.content()))
            .getFirst();
    return tx.execute(
        status -> {
          reauthorize(actor, authorization);
          editable(actor, str(version, "id"), num(version, "revision"));
          if (db.exec(
                  "UPDATE chunks SET content=?,token_count=?,enabled=?,revision=revision+1,origin='MANUAL_EDIT' WHERE tenant_id=? AND id=? AND revision=?",
                  input.content(),
                  count,
                  input.enabled(),
                  actor.tenant(),
                  id,
                  input.revision())
              != 1) throw ApiException.conflict();
          advance(actor, str(version, "id"), num(version, "revision"));
          db.exec(
              "INSERT INTO chunk_revisions(id,tenant_id,chunk_id,revision,previous_content,actor_id,reason) VALUES(?,?,?,?,?,?,?)",
              id(),
              actor.tenant(),
              id,
              input.revision(),
              row.get("content"),
              actor.subject(),
              input.reason());
          remember(actor, str(version, "id"), "EDIT", List.of(row), List.of(id), input.reason());
          return db.one("SELECT * FROM chunks WHERE tenant_id=? AND id=?", actor.tenant(), id);
        });
  }

  private List<Map<String, Object>> targets(Actor actor, String version, List<Ref> refs) {
    Set<String> seen = new HashSet<>();
    List<Map<String, Object>> rows = new ArrayList<>();
    for (var ref : refs) {
      if (!seen.add(ref.id())) throw new IllegalArgumentException();
      var row =
          db.one(
              "SELECT * FROM chunks WHERE tenant_id=? AND version_id=? AND id=?",
              actor.tenant(),
              version,
              ref.id());
      if (num(row, "revision") != ref.revision()) throw ApiException.conflict();
      rows.add(row);
    }
    rows.sort(Comparator.comparingLong(row -> num(row, "ordinal_no")));
    return rows;
  }

  public Object operate(Actor actor, String authorization, String version, Operation input) {
    var snapshot = editable(actor, version, input.revision());
    var before = targets(actor, version, input.chunks());
    boolean structural = Set.of("SPLIT", "MERGE").contains(input.action());
    if (structural && before.stream().anyMatch(row -> !str(row, "context_id").isBlank()))
      throw new ApiException(409, "CONTEXT_EDIT_REQUIRED", "拆分或合并前请先解除关联，避免破坏上下文分组");
    List<String> contents = structural ? contents(before, input) : List.of();
    List<Long> counts = budget.count(actor.tenant(), str(snapshot, "configuration_id"), contents);
    return tx.execute(
        status -> {
          reauthorize(actor, authorization);
          editable(actor, version, input.revision());
          targets(actor, version, input.chunks());
          List<String> results = new ArrayList<>();
          if (structural) {
            if (input.action().equals("MERGE")
                && num(
                        db.one(
                            "SELECT COUNT(*) AS n FROM chunks WHERE tenant_id=? AND version_id=? AND ordinal_no BETWEEN ? AND ?",
                            actor.tenant(),
                            version,
                            before.getFirst().get("ordinal_no"),
                            before.getLast().get("ordinal_no")),
                        "n")
                    != before.size())
              throw new ApiException(400, "NON_ADJACENT_CHUNKS", "只能合并相邻切片");
            results.addAll(replace(actor, version, before, contents, counts, input.action()));
          } else {
            if (input.action().equals("SET_ENABLED") && input.enabled() == null
                || input.action().equals("TAGS") && input.tags() == null)
              throw new IllegalArgumentException();
            for (var row : before) {
              String target = str(row, "id");
              if (input.action().equals("SET_ENABLED"))
                db.exec(
                    "UPDATE chunks SET enabled=?,revision=revision+1 WHERE tenant_id=? AND id=?",
                    input.enabled(),
                    actor.tenant(),
                    target);
              else
                db.exec(
                    "UPDATE chunks SET tags_json=?,revision=revision+1 WHERE tenant_id=? AND id=?",
                    encode(new LinkedHashSet<>(input.tags())),
                    actor.tenant(),
                    target);
              results.add(target);
            }
          }
          advance(actor, version, input.revision());
          remember(actor, version, input.action(), before, results, input.reason());
          return Map.of("revision", input.revision() + 1, "chunk_ids", results);
        });
  }

  private List<String> contents(List<Map<String, Object>> before, Operation input) {
    if (input.action().equals("MERGE")) {
      if (before.size() < 2
          || before.size() > 20
          || before.stream()
              .anyMatch(row -> bool(row, "enabled") != bool(before.getFirst(), "enabled")))
        throw new IllegalArgumentException();
      return List.of(String.join("\n\n", before.stream().map(row -> str(row, "content")).toList()));
    }
    if (before.size() != 1 || input.split_offsets() == null || input.split_offsets().isEmpty())
      throw new IllegalArgumentException();
    String content = str(before.getFirst(), "content");
    int previous = 0;
    List<String> parts = new ArrayList<>();
    for (int offset : input.split_offsets()) {
      if (offset <= previous
          || offset >= content.length()
          || Character.isLowSurrogate(content.charAt(offset))) throw new IllegalArgumentException();
      parts.add(content.substring(previous, offset));
      previous = offset;
    }
    parts.add(content.substring(previous));
    return parts;
  }

  private List<String> replace(
      Actor actor,
      String version,
      List<Map<String, Object>> before,
      List<String> contents,
      List<Long> counts,
      String operation) {
    long insertion = num(before.getFirst(), "ordinal_no");
    String source =
        String.join("\n\n", before.stream().map(row -> str(row, "source_text")).toList());
    List<Object> origins =
        before.stream()
            .map(
                row ->
                    (Object) Map.of("chunk_id", str(row, "id"), "location", str(row, "location")))
            .toList();
    String location =
        encode(
            Map.of(
                "type",
                "manual_" + operation.toLowerCase(Locale.ROOT),
                "sources",
                origins,
                "warning",
                "人工拆分或合并，原文为来源范围，不与新切片逐字对齐"));
    if (location.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 60000
        || source.length() > 240000)
      throw new ApiException(400, "SOURCE_TOO_LARGE", "来源记录过大，请分批处理");
    Set<String> tags = new LinkedHashSet<>();
    for (var row : before)
      try {
        for (var tag : json.readTree(str(row, "tags_json"))) tags.add(tag.asText());
      } catch (Exception error) {
        throw new IllegalArgumentException();
      }
    if (tags.size() > 20) throw new ApiException(400, "TOO_MANY_TAGS", "合并标签超过20个，请先整理标签");
    for (var row : before)
      db.exec(
          "DELETE FROM chunks WHERE tenant_id=? AND version_id=? AND id=?",
          actor.tenant(),
          version,
          row.get("id"));
    // Move ordinals via a disjoint negative range to avoid transient unique-key collisions.
    var tail =
        db.list(
            "SELECT id FROM chunks WHERE tenant_id=? AND version_id=? AND ordinal_no>=? ORDER BY ordinal_no",
            actor.tenant(),
            version,
            insertion);
    db.exec(
        "UPDATE chunks SET ordinal_no=-ordinal_no-1 WHERE tenant_id=? AND version_id=? AND ordinal_no>=?",
        actor.tenant(),
        version,
        insertion);
    List<String> ids = new ArrayList<>();
    long ordinal = insertion;
    for (int i = 0; i < contents.size(); i++) {
      String next = id();
      ids.add(next);
      db.exec(
          "INSERT INTO chunks(id,tenant_id,version_id,ordinal_no,source_text,content,location,token_count,enabled,origin,tags_json) VALUES(?,?,?,?,?,?,?,?,?,'MANUAL_EDIT',?)",
          next,
          actor.tenant(),
          version,
          ordinal++,
          source,
          contents.get(i),
          location,
          counts.get(i),
          before.getFirst().get("enabled"),
          encode(tags));
    }
    for (var row : tail)
      db.exec(
          "UPDATE chunks SET ordinal_no=? WHERE tenant_id=? AND id=?",
          ordinal++,
          actor.tenant(),
          row.get("id"));
    return ids;
  }
}
