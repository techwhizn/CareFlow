package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DocumentContextService {
  public record Change(@NotNull @Min(0) Long revision, @NotBlank @Size(max = 1000) String reason) {}

  public record Faq(
      @NotNull @Min(0) Long revision,
      @NotBlank @Size(max = 1000) String question,
      @NotNull @Size(max = 20) List<@NotBlank @Size(max = 400) String> alternatives,
      @NotBlank @Size(max = 8000) String answer,
      @NotBlank @Size(max = 1000) String reason) {}

  private final Db db;
  private final Identity auth;
  private final TransactionTemplate tx;
  private final WorkerClient worker;
  private final KnowledgeConfigurationService configurations;
  private final ObjectMapper json;

  public DocumentContextService(
      Db db,
      Identity auth,
      TransactionTemplate tx,
      WorkerClient worker,
      KnowledgeConfigurationService configurations,
      ObjectMapper json) {
    this.db = db;
    this.auth = auth;
    this.tx = tx;
    this.worker = worker;
    this.configurations = configurations;
    this.json = json;
  }

  private Map<String, Object> editable(Actor actor, String version, long revision) {
    var row = auth.version(actor, version, "edit");
    var document = auth.document(actor, str(row, "document_id"), "edit");
    var kb = auth.kb(actor, str(document, "kb_id"), "edit");
    if (!str(document, "status").equals("ACTIVE") || !str(kb, "status").equals("ACTIVE"))
      throw ApiException.hidden();
    if (bool(row, "ever_published"))
      throw new ApiException(409, "IMMUTABLE_PUBLICATION", "请先复制为草稿");
    if (!Set.of("PARSED", "READY").contains(str(row, "state")))
      throw new ApiException(409, "NOT_READY", "请等待解析完成");
    if (num(row, "revision") != revision) throw ApiException.conflict();
    return row;
  }

  private void advance(Actor actor, String version, long revision) {
    if (db.exec(
            "UPDATE document_versions SET revision=revision+1,state='PARSED' WHERE tenant_id=? AND id=? AND revision=? AND ever_published=FALSE",
            actor.tenant(),
            version,
            revision)
        != 1) throw ApiException.conflict();
  }

  public Object detach(
      Actor actor, String authorization, String version, String context, Change change) {
    return tx.execute(
        status -> {
          auth.lock(actor);
          if (!actor.equals(auth.authenticate(authorization))) throw ApiException.hidden();
          editable(actor, version, change.revision());
          var row =
              db.one(
                  "SELECT * FROM chunk_contexts WHERE tenant_id=? AND version_id=? AND id=?",
                  actor.tenant(),
                  version,
                  context);
          remember(actor, version, context, row, change.reason());
          db.exec(
              "UPDATE chunks SET context_id=NULL,revision=revision+1 WHERE tenant_id=? AND version_id=? AND context_id=?",
              actor.tenant(),
              version,
              context);
          advance(actor, version, change.revision());
          auth.audit(actor, "CHUNK_CONTEXT_DETACH", context, change.reason());
          return Map.of("revision", change.revision() + 1);
        });
  }

  public Object saveFaq(
      Actor actor, String authorization, String version, String context, Faq input) {
    var source = editable(actor, version, input.revision());
    if (context != null) requireFaq(actor, version, context);
    var runtime = configurations.runtime(actor.tenant(), str(source, "configuration_id"));
    if (runtime == null) throw new ApiException(409, "CONFIGURATION_REQUIRED", "请先按已发布配置创建处理版本");
    var generated =
        worker.call(
            "/internal/v1/faq/chunk",
            new WorkerProtocolV1.ManualFaqRequest(
                input.question(),
                input.alternatives(),
                input.answer(),
                runtime.chunking(),
                runtime.embedding()));
    var parsed = json.convertValue(generated, WorkerProtocolV1.ParsedDocument.class);
    if (parsed.contexts().size() != 1
        || !parsed.contexts().getFirst().kind().equals("FAQ")
        || parsed.chunks().isEmpty())
      throw new ApiException(503, "INVALID_FAQ_RESULT", "FAQ处理结果不完整");
    // No model request holds a database transaction. Recheck permissions and revision after it.
    return tx.execute(
        status -> {
          auth.lock(actor);
          if (!actor.equals(auth.authenticate(authorization))) throw ApiException.hidden();
          editable(actor, version, input.revision());
          String target = context == null ? id() : context;
          boolean enabled = true;
          Set<String> tags = new LinkedHashSet<>();
          if (context != null) {
            var previous = requireFaq(actor, version, context);
            var children =
                db.list(
                    "SELECT * FROM chunks WHERE tenant_id=? AND version_id=? AND context_id=?",
                    actor.tenant(),
                    version,
                    context);
            Set<Boolean> states = new HashSet<>();
            for (var child : children) {
              states.add(bool(child, "enabled"));
              try {
                for (var tag : json.readTree(str(child, "tags_json"))) tags.add(tag.asText());
              } catch (Exception error) {
                throw new IllegalArgumentException();
              }
            }
            if (states.size() > 1 || tags.size() > 20)
              throw new ApiException(409, "FAQ_METADATA_CONFLICT", "请先统一该FAQ各切片的启用状态，并将标签整理到20个以内");
            enabled = states.isEmpty() || states.contains(true);
            previous = new HashMap<>(previous);
            previous.put("children", children);
            remember(actor, version, context, previous, input.reason());
            db.exec(
                "DELETE FROM chunks WHERE tenant_id=? AND version_id=? AND context_id=?",
                actor.tenant(),
                version,
                context);
          }
          var parent = parsed.contexts().getFirst();
          String location = "{\"type\":\"manual\",\"warning\":\"人工补充或修订，无原文件定位\"}";
          if (context == null) {
            long ordinal =
                num(
                    db.one(
                        "SELECT COALESCE(MAX(ordinal_no),-1)+1 AS next_ordinal FROM chunk_contexts WHERE tenant_id=? AND version_id=?",
                        actor.tenant(),
                        version),
                    "next_ordinal");
            db.exec(
                "INSERT INTO chunk_contexts(id,tenant_id,version_id,ordinal_no,kind,source_text,content,location,token_count,question,alternatives_json,origin,answer) VALUES(?,?,?,?,'FAQ','',?,?,?,?,?,'MANUAL',?)",
                target,
                actor.tenant(),
                version,
                ordinal,
                parent.content(),
                location,
                parent.token_count(),
                input.question(),
                encode(input.alternatives()),
                input.answer());
          } else
            db.exec(
                "UPDATE chunk_contexts SET source_text='',content=?,location=?,token_count=?,question=?,alternatives_json=?,origin='MANUAL',answer=? WHERE tenant_id=? AND version_id=? AND id=?",
                parent.content(),
                location,
                parent.token_count(),
                input.question(),
                encode(input.alternatives()),
                input.answer(),
                actor.tenant(),
                version,
                target);
          long ordinal =
              num(
                  db.one(
                      "SELECT COALESCE(MAX(ordinal_no),-1)+1 AS next_ordinal FROM chunks WHERE tenant_id=? AND version_id=?",
                      actor.tenant(),
                      version),
                  "next_ordinal");
          for (var chunk : parsed.chunks())
            db.exec(
                "INSERT INTO chunks(id,tenant_id,version_id,ordinal_no,source_text,content,location,token_count,context_id,origin,enabled,tags_json) VALUES(?,?,?,?,'',?,?,?,?,'MANUAL',?,?)",
                id(),
                actor.tenant(),
                version,
                ordinal++,
                chunk.content(),
                location,
                chunk.token_count(),
                target,
                enabled,
                encode(tags));
          advance(actor, version, input.revision());
          auth.audit(actor, "FAQ_SAVE", target, input.reason());
          return Map.of("id", target, "revision", input.revision() + 1);
        });
  }

  private Map<String, Object> requireFaq(Actor actor, String version, String id) {
    var context =
        db.one(
            "SELECT * FROM chunk_contexts WHERE tenant_id=? AND version_id=? AND id=? AND kind='FAQ'",
            actor.tenant(),
            version,
            id);
    return context;
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException();
    }
  }

  private void remember(
      Actor actor, String version, String context, Map<String, Object> previous, String reason) {
    db.exec(
        "INSERT INTO chunk_context_revisions(id,tenant_id,version_id,context_id,previous_json,actor_id,reason) VALUES(?,?,?,?,?,?,?)",
        id(),
        actor.tenant(),
        version,
        context,
        encode(previous),
        actor.subject(),
        reason);
  }
}
