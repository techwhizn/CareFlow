package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import com.careflow.platform.RetrievalService.Query;
import com.careflow.platform.RetrievalService.Scope;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ConversationService {
  public record Context(
      String id,
      long revision,
      List<WorkerProtocolV1.ConversationTurn> turns,
      List<Map<String, Object>> dependencies,
      int tokens,
      String previousQuestion) {}

  private final ConversationRepository repository;
  private final AnswerHistoryService history;
  private final WorkerClient worker;
  private final Identity auth;
  private final EvidenceAuthorization evidence;
  private final Db db;

  public ConversationService(
      ConversationRepository repository,
      AnswerHistoryService history,
      WorkerClient worker,
      Identity auth,
      EvidenceAuthorization evidence,
      Db db) {
    this.repository = repository;
    this.history = history;
    this.worker = worker;
    this.auth = auth;
    this.evidence = evidence;
    this.db = db;
  }

  public Map<String, Object> create(Actor actor, String app, List<String> bases) {
    return view(repository.owned(actor, repository.create(actor, app, bases)));
  }

  private Map<String, Object> view(Map<String, Object> row) {
    var result = new LinkedHashMap<>(row);
    result.remove("active_request_id");
    result.remove("busy_until");
    result.put("busy", !str(row, "active_request_id").isBlank());
    return result;
  }

  public List<Map<String, Object>> list(Actor actor) {
    return db
        .list(
            "SELECT * FROM conversations WHERE tenant_id=? AND subject_id=? AND subject_kind=? ORDER BY updated_at DESC,id DESC LIMIT 100",
            actor.tenant(),
            actor.subject(),
            actor.kind())
        .stream()
        .filter(
            row -> {
              try {
                repository.owned(actor, str(row, "id"));
                return true;
              } catch (ApiException error) {
                if (error.status != 404) throw error;
                return false;
              }
            })
        .map(this::view)
        .toList();
  }

  public Map<String, Object> get(Actor actor, String id) {
    var result = view(repository.owned(actor, id));
    result.put("answers", history.history(actor, id));
    return result;
  }

  @SuppressWarnings("unchecked")
  public Context prepare(
      Actor actor, Query query, Scope scope, String request, String authorization) {
    String id =
        query.conversation_id() == null || query.conversation_id().isBlank()
            ? repository.create(actor, scope.application(), query.knowledge_base_ids())
            : UUID.fromString(query.conversation_id()).toString();
    var claimed =
        repository.claim(actor, id, scope.application(), query.knowledge_base_ids(), request);
    var candidates = history.recent(actor, id);
    Map<String, Long> counts = new HashMap<>();
    if (!candidates.isEmpty()) {
      if (!auth.authenticate(authorization).equals(actor)) throw ApiException.hidden();
      var response =
          worker.call(
              "/internal/v1/conversation/tokens",
              Map.of(
                  "candidates",
                  candidates.stream()
                      .map(
                          row ->
                              Map.of(
                                  "id",
                                  str(row, "id"),
                                  "content",
                                  str(row, "question") + "\n" + str(row, "content")))
                      .toList()));
      if (!"cl100k_base".equals(response.get("tokenizer")))
        throw new IllegalStateException("Invalid conversation tokenizer");
      for (var row : (List<Map<String, Object>>) response.get("counts")) {
        if (counts.put(str(row, "id"), num(row, "token_count")) != null)
          throw new IllegalStateException("Duplicate conversation count");
      }
      if (!counts
          .keySet()
          .equals(
              candidates.stream()
                  .map(row -> str(row, "id"))
                  .collect(java.util.stream.Collectors.toSet())))
        throw new IllegalStateException("Missing conversation counts");
    }
    List<Map<String, Object>> selected = new ArrayList<>();
    int total = 0;
    for (var candidate : candidates) {
      Long count = counts.get(str(candidate, "id"));
      if (count == null || count < 1 || count > 100000)
        throw new IllegalStateException("Invalid conversation Token count");
      if (total + count > 3000) break;
      history.validate(actor, candidate);
      selected.add(candidate);
      total += count.intValue();
    }
    String previous = selected.isEmpty() ? "" : str(selected.getFirst(), "question");
    for (var candidate : selected)
      if (!QueryProcessing.process(str(candidate, "question")).identifiers().isEmpty()) {
        previous = str(candidate, "question");
        break;
      }
    Collections.reverse(selected);
    var dependencies = new LinkedHashMap<String, Map<String, Object>>();
    for (var answer : selected)
      for (var source : history.dependencies(str(answer, "id")))
        dependencies.put(str(source, "id"), source);
    var context =
        new Context(
            id,
            num(claimed, "revision"),
            selected.stream()
                .map(
                    row ->
                        new WorkerProtocolV1.ConversationTurn(
                            str(row, "question"), str(row, "content")))
                .toList(),
            List.copyOf(dependencies.values()),
            total,
            previous);
    if (!auth.authenticate(authorization).equals(actor)) throw ApiException.hidden();
    check(actor, context, request, scope);
    return context;
  }

  public void check(Actor actor, Context context, String request, Scope scope) {
    repository.fence(actor, context.id(), request);
    for (var dependency : context.dependencies()) evidence.check(actor, dependency, scope);
  }

  public void release(Actor actor, String request) {
    repository.release(actor, request);
  }
}
