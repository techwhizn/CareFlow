package com.careflow.sdk;

import java.util.List;

/** Immutable application query policy; indexed embedding identities remain with the KB. */
public record ApplicationConfiguration(
    List<String> knowledge_base_ids,
    long revision,
    boolean allow_degraded,
    String owner_id,
    KnowledgeConfiguration.Retrieval retrieval,
    Models models,
    AnswerPolicy answer_policy) {
  public record Models(
      String rerank_profile_id,
      long rerank_profile_revision,
      String generation_profile_id,
      long generation_profile_revision) {}

  public record AnswerPolicy(
      String language,
      String style,
      int maximum_output_tokens,
      int history_rounds,
      int history_tokens) {
    public AnswerPolicy() {
      this("auto", "standard", 2048, 6, 3000);
    }
  }
}
