package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AnswerCitationService {
  private final AnswerHistoryService history;
  private final Db db;

  public AnswerCitationService(AnswerHistoryService history, Db db) {
    this.history = history;
    this.db = db;
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> source(Actor actor, String answerId, String citationId) {
    var answer = history.answer(actor, answerId);
    var citation =
        ((List<Map<String, Object>>) answer.get("evidence"))
            .stream()
                .filter(row -> citationId.equals(str(row, "id")))
                .findFirst()
                .orElseThrow(ApiException::hidden);
    var result = new LinkedHashMap<>(citation);
    boolean snapshot = citation.get("source_chunks") instanceof List<?>;
    if (!snapshot) {
      var ids =
          citation.get("covered_chunk_ids") instanceof List<?> covered
              ? covered
              : List.of(citationId);
      var originals = new ArrayList<Map<String, Object>>();
      for (Object id : ids)
        originals.add(
            db.one(
                "SELECT id,version_id,revision,source_text,content,location,origin FROM chunks WHERE tenant_id=? AND version_id=? AND id=? AND enabled=TRUE",
                actor.tenant(),
                str(citation, "version_id"),
                id));
      result.put("source_chunks", originals);
    }
    result.put("source_snapshot", snapshot);
    result.put("answer_id", answerId);
    result.put(
        "filename",
        str(
            db.one(
                "SELECT filename FROM document_versions WHERE tenant_id=? AND id=?",
                actor.tenant(),
                str(citation, "version_id")),
            "filename"));
    return result;
  }
}
