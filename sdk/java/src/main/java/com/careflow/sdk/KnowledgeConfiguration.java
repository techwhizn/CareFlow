package com.careflow.sdk;

/** Model references contain the administrator-observed revisions, never model credentials. */
public record KnowledgeConfiguration(
    String name, Parsing parsing, Chunking chunking, Retrieval retrieval, Models models) {
  public record Parsing(int pdf_page_limit) {}

  public record Chunking(int target, int maximum, int overlap) {}

  public record Retrieval(
      String mode, int limit, Double minimum_rerank_score, boolean allow_degraded) {}

  public record Models(
      String embedding_profile_id,
      String rerank_profile_id,
      String generation_profile_id,
      long embedding_profile_revision,
      long rerank_profile_revision,
      long generation_profile_revision) {}
}
