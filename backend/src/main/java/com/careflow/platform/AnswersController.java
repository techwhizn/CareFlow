package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import com.careflow.platform.RetrievalService.Query;
import com.careflow.platform.RetrievalService.Scope;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
public class AnswersController {
  private final Db db;
  private final Identity auth;
  private final RetrievalService retrieval;
  private final WorkerClient worker;

  public AnswersController(Db db, Identity auth, RetrievalService retrieval, WorkerClient worker) {
    this.db = db;
    this.auth = auth;
    this.retrieval = retrieval;
    this.worker = worker;
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
    Scope scope = retrieval.scope(actor, q);
    String event = retrieval.reserve(actor, key, scope.application());
    SseEmitter emitter = new SseEmitter(120000L);
    AtomicBoolean stopped = new AtomicBoolean(false);
    emitter.onCompletion(() -> stopped.set(true));
    emitter.onTimeout(() -> stopped.set(true));
    emitter.onError(e -> stopped.set(true));
    Thread.startVirtualThread(
        () -> {
          boolean charged = false;
          try {
            emitter.send(SseEmitter.event().name("start").data(Map.of("request_id", event)));
            emitter.send(SseEmitter.event().name("status").data(Map.of("stage", "retrieval")));
            var result = retrieval.search(actor, q, scope, authorization);
            @SuppressWarnings("unchecked")
            var evidence = (List<Map<String, Object>>) result.get("evidence");
            StringBuilder content = new StringBuilder();
            if (evidence.isEmpty()) {
              content.append("当前可访问的资料中没有找到足够依据，无法确认答案。");
              emitter.send(
                  SseEmitter.event().name("delta").data(Map.of("text", content.toString())));
            } else {
              emitter.send(SseEmitter.event().name("status").data(Map.of("stage", "generation")));
              worker.stream(
                  Map.of(
                      "query",
                      q.query(),
                      "evidence",
                      evidence.stream()
                          .map(c -> Map.of("id", str(c, "id"), "content", str(c, "content")))
                          .toList()),
                  delta -> {
                    if (stopped.get()) throw new ApiException(409, "CANCELLED", "用户已停止接收");
                    retrieval.reauthenticate(actor, authorization);
                    for (var c : evidence) retrieval.checkEvidence(actor, c, scope);
                    content.append(delta);
                    if (content.length() > 32000)
                      throw new ApiException(502, "OUTPUT_LIMIT", "模型输出超限");
                    try {
                      emitter.send(SseEmitter.event().name("delta").data(Map.of("text", delta)));
                    } catch (java.io.IOException e) {
                      throw new IllegalStateException(e);
                    }
                  });
            }
            if (stopped.get()) throw new ApiException(409, "CANCELLED", "用户已停止接收");
            retrieval.reauthenticate(actor, authorization);
            for (var c : evidence) retrieval.checkEvidence(actor, c, scope);
            String id =
                retrieval.saveAnswer(
                    actor,
                    new Query(
                        q.query(),
                        scope.application(),
                        q.knowledge_base_ids(),
                        q.mode(),
                        q.limit(),
                        q.debug(),
                        q.minimum_rerank_score()),
                    content.toString(),
                    evidence);
            charged = true;
            emitter.send(SseEmitter.event().name("citations").data(Map.of("evidence", evidence)));
            emitter.send(
                SseEmitter.event()
                    .name("done")
                    .data(Map.of("answer_id", id, "degraded", result.get("degraded"))));
            emitter.complete();
          } catch (Exception e) {
            try {
              emitter.send(
                  SseEmitter.event()
                      .name("error")
                      .data(
                          Map.of(
                              "code",
                              e instanceof ApiException a ? a.code : "STREAM_INTERRUPTED",
                              "message",
                              "回答未完成，请检查服务状态或当前访问权限")));
              emitter.complete();
            } catch (Exception ignored) {
              emitter.completeWithError(e);
            }
          } finally {
            retrieval.settle(actor, event, charged);
          }
        });
    return emitter;
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
