package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Freezes old manual changes when a new parse is requested; never applies them implicitly. */
@Service
public class ContentConflictService {
  public record Resolution(
      @NotNull @Min(0) Long revision,
      @NotBlank @Pattern(regexp = "KEEP_NEW|APPEND_OLD|APPLY_TO_CHUNK") String action,
      @Valid ChunkMutationService.Ref target,
      @NotBlank @Size(max = 1000) String reason) {}

  private final Db db;
  private final Identity auth;
  private final ChunkMutationService mutations;
  private final ContentBudgetService budget;
  private final ObjectMapper json;
  private final TransactionTemplate tx;

  public ContentConflictService(
      Db db,
      Identity auth,
      ChunkMutationService mutations,
      ContentBudgetService budget,
      ObjectMapper json,
      TransactionTemplate tx) {
    this.db = db;
    this.auth = auth;
    this.mutations = mutations;
    this.budget = budget;
    this.json = json;
    this.tx = tx;
  }

  @Transactional
  public void snapshotLatest(Actor actor, String document, String target) {
    var versions =
        db.list(
            "SELECT id FROM document_versions WHERE tenant_id=? AND document_id=? AND id<>? ORDER BY created_at DESC,sequence_no DESC LIMIT 1",
            actor.tenant(),
            document,
            target);
    if (!versions.isEmpty()) snapshot(actor, str(versions.getFirst(), "id"), target);
  }

  @Transactional
  public void snapshot(Actor actor, String source, String target) {
    auth.version(actor, source, "read");
    for (var row :
        db.list(
            "SELECT * FROM chunks WHERE tenant_id=? AND version_id=? AND (origin<>'PARSED' OR revision>0 OR enabled=FALSE OR tags_json<>'[]') ORDER BY ordinal_no",
            actor.tenant(),
            source))
      db.exec(
          "INSERT INTO content_conflicts(id,tenant_id,version_id,source_version_id,source_chunk_id,snapshot_json) VALUES(?,?,?,?,?,?)",
          id(),
          actor.tenant(),
          target,
          source,
          row.get("id"),
          mutations.encode(row));
    inherit(actor, source, target);
  }

  @Transactional
  public void inherit(Actor actor, String source, String target) {
    for (var row :
        db.list(
            "SELECT * FROM content_conflicts WHERE tenant_id=? AND version_id=? AND resolution IS NULL",
            actor.tenant(),
            source)) {
      if (!db.list(
              "SELECT id FROM content_conflicts WHERE tenant_id=? AND version_id=? AND source_chunk_id=?",
              actor.tenant(),
              target,
              row.get("source_chunk_id"))
          .isEmpty()) continue;
      db.exec(
          "INSERT INTO content_conflicts(id,tenant_id,version_id,source_version_id,source_chunk_id,snapshot_json) VALUES(?,?,?,?,?,?)",
          id(),
          actor.tenant(),
          target,
          row.get("source_version_id"),
          row.get("source_chunk_id"),
          row.get("snapshot_json"));
    }
  }

  public void requireResolved(Actor actor, String version) {
    if (!db.list(
            "SELECT id FROM content_conflicts WHERE tenant_id=? AND version_id=? AND resolution IS NULL LIMIT 1",
            actor.tenant(),
            version)
        .isEmpty())
      throw new ApiException(409, "UNRESOLVED_CONTENT_CONFLICTS", "请先核对旧人工修订与新解析内容的冲突");
  }

  public Object list(Actor actor, String version, int page) {
    auth.version(actor, version, "edit");
    return db
        .list(
            "SELECT * FROM content_conflicts WHERE tenant_id=? AND version_id=? ORDER BY created_at,id LIMIT 100 OFFSET ?",
            actor.tenant(),
            version,
            Math.max(0, page) * 100)
        .stream()
        .map(
            row -> {
              auth.version(actor, str(row, "source_version_id"), "read");
              Map<String, Object> result = new HashMap<>(row);
              result.remove("snapshot_json");
              var old = decode(str(row, "snapshot_json"));
              result.put("previous", old);
              result.put(
                  "matching_chunks",
                  db.list(
                      "SELECT id,ordinal_no,revision,content,enabled,context_id FROM chunks WHERE tenant_id=? AND version_id=? AND source_text=? ORDER BY ordinal_no LIMIT 20",
                      actor.tenant(),
                      version,
                      str(old, "source_text")));
              return result;
            })
        .toList();
  }

  public Object history(Actor actor, String version, int page) {
    auth.version(actor, version, "edit");
    return db.list(
        "SELECT * FROM chunk_changes WHERE tenant_id=? AND version_id=? ORDER BY created_at DESC,id DESC LIMIT 50 OFFSET ?",
        actor.tenant(),
        version,
        Math.max(0, page) * 50);
  }

  public Object resolve(
      Actor actor, String authorization, String version, String id, Resolution input) {
    var source = mutations.editable(actor, version, input.revision());
    var conflict = conflict(actor, version, id);
    var previous = decode(str(conflict, "snapshot_json"));
    boolean applying = !input.action().equals("KEEP_NEW");
    if (input.action().equals("APPLY_TO_CHUNK") && input.target() == null)
      throw new IllegalArgumentException();
    if (input.target() != null) target(actor, version, input.target());
    long count =
        applying
            ? budget
                .count(
                    actor.tenant(),
                    str(source, "configuration_id"),
                    List.of(str(previous, "content")))
                .getFirst()
            : 0;
    return tx.execute(
        status -> {
          mutations.reauthorize(actor, authorization);
          mutations.editable(actor, version, input.revision());
          conflict(actor, version, id);
          String result = null;
          List<Object> before = new ArrayList<>(List.of(previous));
          if (applying) {
            if (input.action().equals("APPEND_OLD")) {
              result = id();
              long ordinal =
                  num(
                      db.one(
                          "SELECT COALESCE(MAX(ordinal_no),-1)+1 AS n FROM chunks WHERE tenant_id=? AND version_id=?",
                          actor.tenant(),
                          version),
                      "n");
              db.exec(
                  "INSERT INTO chunks(id,tenant_id,version_id,ordinal_no,source_text,content,location,token_count,enabled,origin,tags_json) VALUES(?,?,?,?,'',?,?,?,?,'MANUAL',?)",
                  result,
                  actor.tenant(),
                  version,
                  ordinal,
                  previous.get("content"),
                  "{\"type\":\"manual\",\"warning\":\"从旧人工修订补充，无当前原文件定位\"}",
                  count,
                  previous.get("enabled"),
                  previous.get("tags_json"));
            } else {
              var target = target(actor, version, input.target());
              before.add(target);
              result = input.target().id();
              db.exec(
                  "UPDATE chunks SET content=?,token_count=?,enabled=?,tags_json=?,revision=revision+1,origin='MANUAL_EDIT' WHERE tenant_id=? AND id=?",
                  previous.get("content"),
                  count,
                  previous.get("enabled"),
                  previous.get("tags_json"),
                  actor.tenant(),
                  result);
            }
          }
          db.exec(
              "UPDATE content_conflicts SET resolution=?,result_chunk_id=?,actor_id=?,reason=?,resolved_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=? AND resolution IS NULL",
              input.action(),
              result,
              actor.subject(),
              input.reason(),
              actor.tenant(),
              id);
          mutations.advance(actor, version, input.revision());
          mutations.remember(
              actor,
              version,
              "CONFLICT_RESOLVE",
              before,
              result == null ? List.of() : List.of(result),
              input.reason());
          return Map.of("revision", input.revision() + 1, "resolution", input.action());
        });
  }

  private Map<String, Object> conflict(Actor actor, String version, String id) {
    var row =
        db.one(
            "SELECT * FROM content_conflicts WHERE tenant_id=? AND version_id=? AND id=?",
            actor.tenant(),
            version,
            id);
    auth.version(actor, str(row, "source_version_id"), "read");
    if (row.get("resolution") != null) throw ApiException.conflict();
    return row;
  }

  private Map<String, Object> target(Actor actor, String version, ChunkMutationService.Ref ref) {
    var row =
        db.one(
            "SELECT * FROM chunks WHERE tenant_id=? AND version_id=? AND id=?",
            actor.tenant(),
            version,
            ref.id());
    if (num(row, "revision") != ref.revision()) throw ApiException.conflict();
    if (!str(row, "context_id").isBlank())
      throw new ApiException(409, "CONTEXT_EDIT_REQUIRED", "请先通过上下文编辑器处理分组，再核对冲突");
    return row;
  }

  private Map<String, Object> decode(String value) {
    try {
      return json.readValue(
          value, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      throw new IllegalArgumentException();
    }
  }
}
