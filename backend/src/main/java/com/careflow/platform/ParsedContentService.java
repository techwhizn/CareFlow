package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Owns parsed-content persistence inside the caller's fenced task transaction. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ParsedContentService {
  private final Db db;
  private final ObjectMapper json;
  private final Validator validator;

  public ParsedContentService(Db db, ObjectMapper json, Validator validator) {
    this.db = db;
    this.json = json;
    this.validator = validator;
  }

  public void replace(
      String tenant,
      String version,
      Map<String, Object> body,
      KnowledgeConfiguration.Chunking limits) {
    var parsed =
        new WorkerProtocolV1.ParsedDocument(
            convert(body.get("chunks"), WorkerProtocolV1.ParsedChunk.class),
            convert(body.get("contexts"), WorkerProtocolV1.ParsedContext.class));
    if (!validator.validate(parsed).isEmpty())
      throw new IllegalArgumentException("Invalid parsed document");
    Map<Integer, String> contexts = new HashMap<>();
    for (var context : parsed.contexts()) {
      if (contexts.putIfAbsent(context.ordinal(), id()) != null)
        throw new IllegalArgumentException("Duplicate context ordinal");
      if (context.kind().equals("FAQ")
          && (context.question() == null
              || context.question().isBlank()
              || context.answer() == null
              || context.answer().isBlank()))
        throw new IllegalArgumentException("FAQ question required");
      if (context.token_count() > limits.parent_maximum())
        throw new IllegalArgumentException("Context exceeds snapshot limit");
      if (limits.layout().equals("standard")
          || !context.kind().equals(limits.layout().equals("faq") ? "FAQ" : "PARENT"))
        throw new IllegalArgumentException("Context does not match configured strategy");
    }
    Set<Integer> usedContexts = new HashSet<>();
    for (var chunk : parsed.chunks()) {
      if (chunk.token_count() > limits.maximum())
        throw new IllegalArgumentException("Chunk exceeds snapshot limit");
      if (!limits.layout().equals("standard") && chunk.context_ordinal() == null)
        throw new IllegalArgumentException("Context relationship required");
      if (chunk.context_ordinal() != null && !contexts.containsKey(chunk.context_ordinal()))
        throw new IllegalArgumentException("Unknown document-local context");
      if (chunk.context_ordinal() != null) usedContexts.add(chunk.context_ordinal());
    }
    if (!usedContexts.equals(contexts.keySet()))
      throw new IllegalArgumentException("Unreferenced context");
    db.exec("DELETE FROM chunks WHERE tenant_id=? AND version_id=?", tenant, version);
    db.exec("DELETE FROM chunk_contexts WHERE tenant_id=? AND version_id=?", tenant, version);
    for (var context : parsed.contexts()) {
      String alternatives;
      try {
        alternatives =
            json.writeValueAsString(
                context.alternatives() == null ? List.of() : context.alternatives());
      } catch (Exception e) {
        throw new IllegalArgumentException("Invalid FAQ alternatives");
      }
      db.exec(
          "INSERT INTO chunk_contexts(id,tenant_id,version_id,ordinal_no,kind,source_text,content,location,token_count,question,alternatives_json,answer) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
          contexts.get(context.ordinal()),
          tenant,
          version,
          context.ordinal(),
          context.kind(),
          context.source_text(),
          context.content(),
          context.location(),
          context.token_count(),
          context.question(),
          alternatives,
          context.answer());
    }
    int ordinal = 0;
    for (var chunk : parsed.chunks())
      db.exec(
          "INSERT INTO chunks(id,tenant_id,version_id,ordinal_no,source_text,content,location,token_count,context_id) VALUES(?,?,?,?,?,?,?,?,?)",
          id(),
          tenant,
          version,
          ordinal++,
          chunk.source_text(),
          chunk.content(),
          chunk.location(),
          chunk.token_count(),
          contexts.get(chunk.context_ordinal()));
  }

  private <T> List<T> convert(Object value, Class<T> type) {
    if (value == null) return List.of();
    if (!(value instanceof List<?> entries)) throw new IllegalArgumentException();
    return entries.stream().map(entry -> json.convertValue(entry, type)).toList();
  }

  public Map<String, String> copyContexts(String tenant, String source, String target) {
    Map<String, String> ids = new HashMap<>();
    for (var context :
        db.list(
            "SELECT * FROM chunk_contexts WHERE tenant_id=? AND version_id=? ORDER BY ordinal_no",
            tenant,
            source)) {
      String next = id();
      ids.put(str(context, "id"), next);
      db.exec(
          "INSERT INTO chunk_contexts(id,tenant_id,version_id,ordinal_no,kind,source_text,content,location,token_count,question,alternatives_json,origin,answer) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
          next,
          tenant,
          target,
          context.get("ordinal_no"),
          context.get("kind"),
          context.get("source_text"),
          context.get("content"),
          context.get("location"),
          context.get("token_count"),
          context.get("question"),
          context.get("alternatives_json"),
          context.get("origin"),
          context.get("answer"));
    }
    return ids;
  }
}
