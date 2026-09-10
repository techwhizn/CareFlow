package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import com.careflow.platform.RetrievalService.Query;
import com.careflow.platform.RetrievalService.Scope;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
public class AnswersController {
  private final Db db;
  private final Identity auth;
  private final RetrievalService retrieval;
  private final AnswerStreamService streams;

  public AnswersController(
      Db db, Identity auth, RetrievalService retrieval, AnswerStreamService streams) {
    this.db = db;
    this.auth = auth;
    this.retrieval = retrieval;
    this.streams = streams;
  }

  @PostMapping("/retrieval/search")
  public Object search(
      @RequestAttribute Actor actor,
      @RequestHeader("Authorization") String authorization,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Query q) {
    Scope scope = retrieval.scope(actor, q);
    String event = retrieval.reserve(actor, key, scope.application());
    try {
      var result = retrieval.search(actor, q, scope, authorization);
      retrieval.settle(actor, event, true);
      return result;
    } catch (Exception e) {
      retrieval.settle(actor, event, false);
      throw e;
    }
  }

  @PostMapping(value = "/answers", produces = "text/event-stream")
  public SseEmitter answer(
      @RequestAttribute Actor actor,
      @RequestHeader("Authorization") String authorization,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Query q) {
    return streams.answer(actor, authorization, key, q);
  }

  @GetMapping("/answers")
  public Object history(@RequestAttribute Actor actor) {
    List<Map<String, Object>> visible = new ArrayList<>();
    for (var a :
        db.list(
            "SELECT * FROM answers WHERE tenant_id=? AND subject_id=? ORDER BY created_at DESC LIMIT 100",
            actor.tenant(),
            actor.subject())) {
      boolean allowed = true;
      for (var e :
          db.list(
              "SELECT document_id,version_id,chunk_id AS id FROM answer_evidence WHERE answer_id=?",
              str(a, "id"))) {
        try {
          retrieval.checkEvidence(
              actor, e, new Scope(List.of(), false, str(a, "application_id"), -1));
        } catch (ApiException ex) {
          allowed = false;
          break;
        }
      }
      if (allowed) visible.add(a);
    }
    return visible;
  }

  @PostMapping("/answers/{id}/feedback")
  public void feedback(
      @RequestAttribute Actor actor,
      @PathVariable String id,
      @RequestBody Map<String, String> body) {
    String value = body.get("feedback");
    if (!Set.of("helpful", "incorrect").contains(value)) throw new IllegalArgumentException();
    db.one(
        "SELECT id FROM answers WHERE tenant_id=? AND subject_id=? AND id=?",
        actor.tenant(),
        actor.subject(),
        id);
    for (var e :
        db.list("SELECT document_id,version_id FROM answer_evidence WHERE answer_id=?", id))
      auth.document(actor, str(e, "document_id"), "read");
    db.exec("UPDATE answers SET feedback=? WHERE tenant_id=? AND id=?", value, actor.tenant(), id);
  }
}
