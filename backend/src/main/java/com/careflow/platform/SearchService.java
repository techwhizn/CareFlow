package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import com.careflow.platform.RetrievalService.Query;
import org.springframework.stereotype.Service;

@Service
public class SearchService {
  private final RetrievalService retrieval;
  private final QueryRecordService records;

  public SearchService(RetrievalService retrieval, QueryRecordService records) {
    this.retrieval = retrieval;
    this.records = records;
  }

  public Object search(Actor actor, String authorization, String key, Query query) {
    if (query.conversation_id() != null && !query.conversation_id().isBlank())
      throw new IllegalArgumentException("Conversation context is supported by answers only");
    var scope = retrieval.scope(actor, query);
    String event = retrieval.reserve(actor, key, scope.application());
    try {
      var result =
          retrieval.search(
              actor, query, scope, authorization, QueryProcessing.process(query.query()), event);
      retrieval.reauthenticate(actor, authorization);
      records.save(actor, query, scope, result, event);
      retrieval.settle(actor, event, true);
      return result;
    } catch (Exception error) {
      retrieval.settle(actor, event, false);
      throw error;
    }
  }
}
