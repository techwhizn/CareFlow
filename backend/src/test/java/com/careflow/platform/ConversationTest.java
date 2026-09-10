package com.careflow.platform;

import static com.careflow.platform.Db.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class ConversationTest extends ContentTestSupport {
  @Autowired ConversationRepository repository;
  @Autowired ConversationService conversations;
  @Autowired AnswerHistoryService history;

  RetrievalService.Query query(String conversation) {
    return new RetrievalService.Query(
        "怎么操作？", null, List.of(), null, 6, false, null, List.of(), conversation);
  }

  RetrievalService.Scope scope() {
    return new RetrievalService.Scope(List.of(), false, "", -1);
  }

  String session() {
    return repository.create(actor, "", List.of());
  }

  void seed(String conversation, int turn, String question) {
    db.exec(
        "INSERT INTO answers(id,tenant_id,subject_id,application_id,question,content,conversation_id,turn_no) VALUES(?,?,?,'',?,'合成答案',?,?)",
        id(),
        tenant,
        actor.subject(),
        question,
        conversation,
        turn);
    db.exec("UPDATE conversations SET revision=? WHERE id=?", turn, conversation);
  }

  @Test
  void ownershipScopeAndConcurrentRequestsAreFenced() {
    String conversation = session(), first = id(), next = id();
    var foreign =
        new Identity.Actor(id(), actor.subject(), actor.kind(), actor.role(), actor.scopes());
    assertThatThrownBy(() -> repository.owned(foreign, conversation))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> repository.claim(actor, conversation, "", List.of(id()), first))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code).isEqualTo("CONVERSATION_SCOPE_MISMATCH"));
    repository.claim(actor, conversation, "", List.of(), first);
    assertThatThrownBy(() -> repository.claim(actor, conversation, "", List.of(), next))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code).isEqualTo("CONVERSATION_BUSY"));
    db.exec(
        "UPDATE conversations SET busy_until=? WHERE id=?",
        java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)),
        conversation);
    repository.claim(actor, conversation, "", List.of(), next);
    repository.release(actor, first);
    repository.fence(actor, conversation, next);
    assertThatThrownBy(() -> repository.advance(actor, conversation, first))
        .isInstanceOf(ApiException.class);
    assertThat(repository.advance(actor, conversation, next)).isEqualTo(1);
    assertThat(str(repository.owned(actor, conversation), "active_request_id")).isEmpty();
  }

  @Test
  void contextKeepsAtMostSixTurnsAndNewestContiguousBudget() {
    String conversation = session();
    for (int i = 1; i <= 8; i++) seed(conversation, i, "CF-100 问题 " + i);
    when(worker.call(eq("/internal/v1/conversation/tokens"), anyMap()))
        .thenAnswer(
            invocation -> {
              Map<String, Object> body = invocation.getArgument(1);
              @SuppressWarnings("unchecked")
              var rows = (List<Map<String, Object>>) body.get("candidates");
              assertThat(rows).hasSize(6);
              return Map.of(
                  "tokenizer",
                  "cl100k_base",
                  "counts",
                  rows.stream()
                      .map(row -> Map.of("id", row.get("id"), "token_count", 1000))
                      .toList());
            });
    var context = conversations.prepare(actor, query(conversation), scope(), id(), token);
    assertThat(context.tokens()).isEqualTo(3000);
    assertThat(context.turns())
        .extracting(WorkerProtocolV1.ConversationTurn::question)
        .containsExactly("CF-100 问题 6", "CF-100 问题 7", "CF-100 问题 8");
    assertThat(context.revision()).isEqualTo(8);
  }

  @Test
  void malformedTokenizerIdentityFailsBeforeHistoryIsSentToGeneration() {
    String conversation = session();
    seed(conversation, 1, "合成问题");
    when(worker.call(eq("/internal/v1/conversation/tokens"), anyMap()))
        .thenReturn(
            Map.of(
                "tokenizer",
                "cl100k_base",
                "counts",
                List.of(Map.of("id", id(), "token_count", 3001))));
    assertThatThrownBy(
            () -> conversations.prepare(actor, query(conversation), scope(), id(), token))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Missing conversation counts");
  }

  @Test
  void inheritedDependenciesHideLaterAnswersAfterRevocation() {
    db.exec("UPDATE document_versions SET ever_published=TRUE WHERE id=?", version);
    String chunk = chunk(0, "合成证据", 3, true, "page:1");
    String document =
        str(db.one("SELECT document_id FROM document_versions WHERE id=?", version), "document_id");
    var source =
        Map.<String, Object>of(
            "id",
            chunk,
            "document_id",
            document,
            "version_id",
            version,
            "content",
            "原始合成证据",
            "title",
            "合成标题");
    String conversation = session(), first = id();
    repository.claim(actor, conversation, "", List.of(), first);
    String answer =
        history.save(
            actor,
            query(conversation),
            "第一轮答案",
            List.of(source),
            new ConversationService.Context(conversation, 0, List.of(), List.of(), 0, ""),
            first);
    assertThat((List<?>) history.answer(actor, answer).get("evidence")).hasSize(1);
    String second = id();
    repository.claim(actor, conversation, "", List.of(), second);
    String followup =
        history.save(
            actor,
            query(conversation),
            "第二轮答案",
            List.of(),
            new ConversationService.Context(
                conversation, 1, List.of(), history.dependencies(answer), 4, ""),
            second);
    assertThat(history.history(actor, conversation)).hasSize(2);
    db.exec("UPDATE chunks SET content='已经编辑' WHERE id=?", chunk);
    assertThat(history.answer(actor, answer).get("evidence").toString()).contains("原始合成证据");
    db.exec("UPDATE chunks SET enabled=FALSE WHERE id=?", chunk);
    assertThat(history.history(actor, conversation)).isEmpty();
    assertThatThrownBy(() -> history.answer(actor, followup)).isInstanceOf(ApiException.class);
  }

  @Test
  void referenceResolutionPreservesExplicitNewModel() {
    assertThat(QueryProcessing.conversation("如何重置？", "CF-100 离线怎么处理？").rewritten())
        .contains("CF-100", "如何重置");
    assertThat(QueryProcessing.conversation("CF-200 如何重置？", "CF-100 离线怎么处理？").rewritten())
        .isEqualTo("CF-200 如何重置？");
  }
}
