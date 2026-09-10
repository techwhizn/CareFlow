package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import com.careflow.platform.RetrievalService.Query;
import com.careflow.platform.RetrievalService.Scope;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class AnswerStreamService {
  private final RetrievalService retrieval;
  private final WorkerClient worker;
  private final GenerationAccounting accounting;
  private final ConversationService conversations;
  private final AnswerHistoryService history;

  public AnswerStreamService(
      RetrievalService retrieval,
      WorkerClient worker,
      GenerationAccounting accounting,
      ConversationService conversations,
      AnswerHistoryService history) {
    this.retrieval = retrieval;
    this.worker = worker;
    this.accounting = accounting;
    this.conversations = conversations;
    this.history = history;
  }

  public SseEmitter answer(Actor actor, String authorization, String key, Query q) {
    Scope scope = retrieval.scope(actor, q);
    String event = retrieval.reserve(actor, key, scope.application());
    final ConversationService.Context context;
    try {
      context = conversations.prepare(actor, q, scope, event, authorization);
      accounting.initialize(
          actor.tenant(),
          event,
          scope.configuration() == null ? null : scope.configuration().runtime().id());
    } catch (Exception error) {
      conversations.release(actor, event);
      retrieval.settle(actor, event, false);
      throw error;
    }
    SseEmitter emitter = new SseEmitter(120000L);
    StreamCancellation cancellation = new StreamCancellation();
    emitter.onCompletion(() -> cancellation.cancel());
    emitter.onTimeout(() -> cancellation.cancel());
    emitter.onError(e -> cancellation.cancel());
    Thread.startVirtualThread(
        () -> {
          try {
            while (cancellation.active()) {
              Thread.sleep(2000);
              if (cancellation.active()) emitter.send(SseEmitter.event().comment("keepalive"));
            }
          } catch (Exception error) {
            cancellation.cancel();
          }
        });
    Thread.startVirtualThread(
        () -> {
          boolean charged = false;
          try {
            cancellation.bindThread();
            emitter.send(
                SseEmitter.event()
                    .name("start")
                    .data(
                        Map.of(
                            "request_id",
                            event,
                            "conversation_id",
                            context.id(),
                            "conversation_revision",
                            context.revision(),
                            "context_rounds",
                            context.turns().size(),
                            "context_tokens",
                            context.tokens())));
            emitter.send(SseEmitter.event().name("status").data(Map.of("stage", "retrieval")));
            var result =
                retrieval.search(
                    actor,
                    q,
                    scope,
                    authorization,
                    QueryProcessing.conversation(q.query(), context.previousQuestion()));
            cancellation.check();
            if ("SCORE_UNAVAILABLE".equals(result.get("evidence_status")))
              throw new ApiException(503, "RERANK_UNAVAILABLE", "评分服务不可用，无法确认答案依据");
            @SuppressWarnings("unchecked")
            var evidence = (List<Map<String, Object>>) result.get("evidence");
            conversations.check(actor, context, event, scope);
            StringBuilder content = new StringBuilder();
            if (evidence.isEmpty()) {
              content.append("当前可访问的资料中没有找到足够依据，无法确认答案。");
              emitter.send(
                  SseEmitter.event().name("delta").data(Map.of("text", content.toString())));
            } else {
              emitter.send(SseEmitter.event().name("status").data(Map.of("stage", "generation")));
              var generation =
                  retrieval.generationRequest(actor, q, scope, evidence, authorization);
              generation.put("history", context.turns());
              var citations =
                  new CitationGuard(evidence.stream().map(c -> Db.str(c, "id")).toList());
              accounting.started(actor.tenant(), event);
              worker.stream(
                  generation,
                  delta -> {
                    cancellation.check();
                    retrieval.reauthenticate(actor, authorization);
                    conversations.check(actor, context, event, scope);
                    for (var c : evidence) retrieval.checkEvidence(actor, c, scope);
                    String validated = citations.accept(delta);
                    content.append(validated);
                    if (content.length() > 32000)
                      throw new ApiException(502, "OUTPUT_LIMIT", "模型输出超限");
                    try {
                      emitter.send(
                          SseEmitter.event().name("delta").data(Map.of("text", validated)));
                    } catch (java.io.IOException e) {
                      throw new IllegalStateException(e);
                    }
                  },
                  usage -> {
                    accounting.reported(actor.tenant(), event, usage);
                    cancellation.check();
                    retrieval.reauthenticate(actor, authorization);
                    conversations.check(actor, context, event, scope);
                    for (var c : evidence) retrieval.checkEvidence(actor, c, scope);
                    try {
                      emitter.send(SseEmitter.event().name("usage").data(usage));
                    } catch (java.io.IOException error) {
                      throw new IllegalStateException(error);
                    }
                  },
                  cancellation);
              citations.finish();
            }
            cancellation.check();
            retrieval.reauthenticate(actor, authorization);
            for (var c : evidence) retrieval.checkEvidence(actor, c, scope);
            String id =
                history.save(
                    actor,
                    new Query(
                        q.query(),
                        scope.application(),
                        q.knowledge_base_ids(),
                        q.mode(),
                        q.limit(),
                        q.debug(),
                        q.minimum_rerank_score(),
                        q.filters()),
                    content.toString(),
                    evidence,
                    context,
                    event);
            charged = true;
            emitter.send(SseEmitter.event().name("citations").data(Map.of("evidence", evidence)));
            emitter.send(
                SseEmitter.event()
                    .name("done")
                    .data(
                        Map.of(
                            "answer_id",
                            id,
                            "degraded",
                            result.get("degraded"),
                            "conversation_id",
                            context.id(),
                            "conversation_revision",
                            context.revision() + 1)));
            cancellation.finish();
            emitter.complete();
          } catch (Exception e) {
            cancellation.finish();
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
            cancellation.finish();
            Thread.interrupted();
            try {
              accounting.finish(actor.tenant(), event, charged, cancellation.cancelled());
            } finally {
              try {
                conversations.release(actor, event);
              } finally {
                retrieval.settle(actor, event, charged);
              }
            }
          }
        });
    return emitter;
  }
}
