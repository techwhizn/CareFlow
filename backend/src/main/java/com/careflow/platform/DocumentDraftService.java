package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentDraftService {
  private final Db db;
  private final Identity auth;
  private final WorkerClient worker;
  private final KnowledgeConfigurationService configurations;
  private final ParsedContentService parsedContent;

  public DocumentDraftService(
      Db db,
      Identity auth,
      WorkerClient worker,
      KnowledgeConfigurationService configurations,
      ParsedContentService parsedContent) {
    this.db = db;
    this.auth = auth;
    this.worker = worker;
    this.configurations = configurations;
    this.parsedContent = parsedContent;
  }

  public record Edit(
      @NotBlank @Size(max = 2000) String content,
      boolean enabled,
      long revision,
      @NotBlank @Size(max = 1000) String reason) {}

  @Transactional
  public Object edit(Actor actor, String id, Edit body) {
    auth.lock(actor);
    var c = db.one("SELECT * FROM chunks WHERE tenant_id=? AND id=?", actor.tenant(), id);
    var v = auth.version(actor, str(c, "version_id"), "edit");
    if (!str(c, "context_id").isBlank())
      throw new ApiException(409, "CONTEXT_EDIT_REQUIRED", "此片段属于父子或FAQ分组，请先解除分组关联再单独修订");
    if (bool(v, "ever_published"))
      throw new ApiException(409, "IMMUTABLE_PUBLICATION", "已发布版本不可修改，请先复制为草稿");
    if (!Set.of("PARSED", "READY", "FAILED").contains(str(v, "state")))
      throw new ApiException(409, "PROCESSING", "任务执行中不可编辑");
    String configuration = str(v, "configuration_id");
    Map<String, Object> tokenRequest = new HashMap<>(Map.of("text", body.content()));
    if (!configuration.isBlank()) {
      var runtime = configurations.runtime(actor.tenant(), configuration);
      tokenRequest.put("model_configuration", runtime.embedding());
      tokenRequest.put("model_tokenizer", runtime.chunking().model_tokenizer());
      tokenRequest.put("model_maximum", runtime.chunking().model_maximum());
    }
    var tokenized = worker.call("/internal/v1/tokenize", tokenRequest);
    long tokens = num(tokenized, "token_count");
    int maximum =
        configuration.isBlank()
            ? 600
            : configurations.definition(actor.tenant(), configuration).chunking().maximum();
    if (tokens > maximum) throw new ApiException(400, "CHUNK_TOO_LONG", "切片超过配置的Token上限，请缩短后保存");
    if (tokenized.get("model_token_count") != null
        && num(tokenized, "model_token_count") > num(tokenized, "model_limit"))
      throw new ApiException(400, "MODEL_INPUT_TOO_LONG", "切片超过模型实际输入窗口，请拆分后保存");
    if (db.exec(
            "UPDATE chunks SET content=?,token_count=?,enabled=?,revision=revision+1,origin='MANUAL_EDIT' WHERE tenant_id=? AND id=? AND revision=?",
            body.content(),
            tokens,
            body.enabled(),
            actor.tenant(),
            id,
            body.revision())
        != 1) throw ApiException.conflict();
    db.exec(
        "UPDATE document_versions SET state='PARSED',revision=revision+1 WHERE tenant_id=? AND id=?",
        actor.tenant(),
        str(v, "id"));
    db.exec(
        "INSERT INTO chunk_revisions(id,tenant_id,chunk_id,revision,previous_content,actor_id,reason) VALUES(?,?,?,?,?,?,?)",
        id(),
        actor.tenant(),
        id,
        num(c, "revision"),
        str(c, "content"),
        actor.subject(),
        body.reason());
    auth.audit(actor, "CHUNK_EDIT", id, "reason=" + body.reason());
    return db.one("SELECT * FROM chunks WHERE tenant_id=? AND id=?", actor.tenant(), id);
  }

  @Transactional
  public Object draft(Actor actor, String id) {
    auth.lock(actor);
    var v = auth.version(actor, id, "edit");
    if (!Set.of("PARSED", "READY", "FAILED").contains(str(v, "state"))
        || db.list(
                "SELECT id FROM chunks WHERE tenant_id=? AND version_id=? LIMIT 1",
                actor.tenant(),
                id)
            .isEmpty())
      throw new ApiException(409, "DRAFT_SOURCE_NOT_READY", "源版本尚未完成解析，请等待任务完成后复制草稿");
    String next = id();
    db.exec(
        "INSERT INTO document_versions(id,tenant_id,document_id,object_key,filename,digest,state,size_bytes,configuration_id) VALUES(?,?,?,?,?,?,'PARSED',?,?)",
        next,
        actor.tenant(),
        str(v, "document_id"),
        str(v, "object_key"),
        str(v, "filename"),
        str(v, "digest"),
        v.get("size_bytes"),
        v.get("configuration_id"));
    var contexts = parsedContent.copyContexts(actor.tenant(), id, next);
    for (var c :
        db.list("SELECT * FROM chunks WHERE tenant_id=? AND version_id=?", actor.tenant(), id))
      db.exec(
          "INSERT INTO chunks(id,tenant_id,version_id,ordinal_no,source_text,content,location,token_count,enabled,context_id,origin) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
          id(),
          actor.tenant(),
          next,
          c.get("ordinal_no"),
          c.get("source_text"),
          c.get("content"),
          c.get("location"),
          c.get("token_count"),
          c.get("enabled"),
          contexts.get(str(c, "context_id")),
          c.get("origin"));
    auth.audit(actor, "DRAFT_CREATE", next, id);
    return Map.of("id", next);
  }

  public record BindConfiguration(@Min(0) long revision) {}

  @Transactional
  public Object bindConfiguration(Actor actor, String id, BindConfiguration input) {
    auth.lock(actor);
    var version = auth.version(actor, id, "publish");
    var document = auth.document(actor, str(version, "document_id"), "edit");
    var kb = auth.kb(actor, str(document, "kb_id"), "manage");
    if (!str(kb, "status").equals("ACTIVE") || !str(document, "status").equals("ACTIVE"))
      throw ApiException.hidden();
    if (!str(version, "configuration_id").isBlank() || !str(version, "state").equals("READY"))
      throw new ApiException(409, "CONFIGURATION_ALREADY_BOUND", "只有尚未绑定配置的就绪索引可以执行兼容绑定");
    String configuration = str(kb, "published_configuration");
    if (configuration.isBlank())
      throw new ApiException(409, "CONFIGURATION_REQUIRED", "请先发布与原索引匹配的知识库配置");
    var runtime = configurations.runtime(actor.tenant(), configuration);
    if (!configurations.modelIdentity(runtime.embedding()).equals(str(version, "model_identity")))
      throw new ApiException(409, "INDEX_MODEL_MISMATCH", "当前配置与原索引模型身份不同，请重新处理为新版本");
    if (db.exec(
            "UPDATE document_versions SET configuration_id=?,revision=revision+1 WHERE tenant_id=? AND id=? AND configuration_id IS NULL AND revision=?",
            configuration,
            actor.tenant(),
            id,
            input.revision())
        != 1) throw ApiException.conflict();
    auth.audit(actor, "INDEX_CONFIGURATION_BIND", id, configuration);
    return Map.of("configuration_id", configuration, "revision", input.revision() + 1);
  }

  @Transactional
  public Object reprocess(Actor actor, String id, String key) {
    auth.lock(actor);
    var source = auth.version(actor, id, "edit");
    var document = auth.document(actor, str(source, "document_id"), "edit");
    var kb = auth.kb(actor, str(document, "kb_id"), "edit");
    if (!str(kb, "status").equals("ACTIVE") || !str(document, "status").equals("ACTIVE"))
      throw ApiException.hidden();
    String configuration = str(kb, "published_configuration");
    if (configuration.isBlank()) throw new ApiException(409, "CONFIGURATION_REQUIRED", "请先发布知识库配置");
    if (key == null || key.isBlank() || key.length() > 100) throw new IllegalArgumentException();
    var previous =
        db.list(
            "SELECT id,version_id FROM jobs WHERE tenant_id=? AND request_key=?",
            actor.tenant(),
            key);
    if (!previous.isEmpty()) throw new ApiException(409, "DUPLICATE_REQUEST", "此处理请求已提交，请查看任务中心");
    String next = id(), job = id();
    db.exec(
        "INSERT INTO document_versions(id,tenant_id,document_id,object_key,filename,digest,size_bytes,configuration_id) VALUES(?,?,?,?,?,?,?,?)",
        next,
        actor.tenant(),
        str(source, "document_id"),
        str(source, "object_key"),
        str(source, "filename"),
        str(source, "digest"),
        source.get("size_bytes"),
        configuration);
    db.exec(
        "INSERT INTO jobs(id,tenant_id,version_id,kind,request_key,configuration_id) VALUES(?,?,?,'PARSE',?,?)",
        job,
        actor.tenant(),
        next,
        key,
        configuration);
    db.exec("INSERT INTO outbox(id,job_id) VALUES(?,?)", id(), job);
    auth.audit(
        actor,
        "DOCUMENT_REPROCESS",
        str(source, "document_id"),
        "source=" + id + ",configuration=" + configuration);
    return Map.of("version_id", next, "job_id", job);
  }

  @Transactional
  public Object index(Actor actor, String id, String key) {
    auth.lock(actor);
    var v = auth.version(actor, id, "edit");
    if (bool(v, "ever_published"))
      throw new ApiException(409, "IMMUTABLE_PUBLICATION", "已发布版本不可重新索引");
    if (!Set.of("PARSED", "FAILED").contains(str(v, "state")))
      throw new ApiException(409, "NOT_READY", "请先完成解析或修改切片");
    if (key.isBlank() || key.length() > 100) throw new IllegalArgumentException();
    String job = id();
    db.exec(
        "INSERT INTO jobs(id,tenant_id,version_id,kind,request_key,configuration_id) VALUES(?,?,?,'INDEX',?,?)",
        job,
        actor.tenant(),
        id,
        key,
        v.get("configuration_id"));
    db.exec("INSERT INTO outbox(id,job_id) VALUES(?,?)", id(), job);
    db.exec(
        "UPDATE document_versions SET state='QUEUED' WHERE tenant_id=? AND id=?",
        actor.tenant(),
        id);
    return Map.of("job_id", job);
  }
}
