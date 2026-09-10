package com.careflow.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

/** Wire contract for /internal/v1. Domain code does not trust returned content as authority. */
public final class WorkerProtocolV1 {
  private WorkerProtocolV1() {}

  public record Candidate(
      @NotBlank @Size(max = 100) String id, @NotBlank @Size(max = 10000) String content) {}

  public record Hit(@NotBlank @Size(max = 100) String id, Double score) {
    public Hit {
      if (score != null && !Double.isFinite(score))
        throw new IllegalArgumentException("Non-finite score");
    }
  }

  public record ModelConfiguration(
      @NotNull @Pattern(regexp = "EMBEDDING|RERANK|GENERATION") String kind,
      @NotBlank @Size(max = 500) String base_url,
      @NotBlank @Size(max = 200) String model,
      @NotNull @Size(max = 200) String revision,
      @Min(1) @Max(65536) Integer dimensions,
      @Size(max = 8192) String api_key) {
    public ModelConfiguration {
      api_key = api_key == null ? "" : api_key;
    }

    @Override
    public String toString() {
      return "ModelConfiguration[redacted]";
    }
  }

  public record RecallRequest(
      @NotBlank String tenant_id,
      @NotNull @Size(max = 10000) List<@NotBlank String> version_ids,
      @Size(min = 1, max = 10000) List<@NotBlank String> generation_ids,
      @NotBlank @Size(max = 4000) String query,
      @NotNull @Pattern(regexp = "hybrid|semantic|keyword") String mode,
      boolean allow_degraded,
      @Valid ModelConfiguration model_configuration,
      @Size(min = 1, max = 500) String expected_model_identity) {}

  public record ModelUsage(
      @NotNull @Pattern(regexp = "NOT_CALLED|REPORTED|NOT_REPORTED|UNKNOWN") String state,
      @Min(0) Long total_tokens) {
    public ModelUsage {
      if ("REPORTED".equals(state) != (total_tokens != null))
        throw new IllegalArgumentException("Inconsistent model usage");
    }
  }

  public record RecallResponse(
      @NotNull @Size(max = 40) List<@NotNull @Valid Hit> dense,
      @NotNull @Size(max = 40) List<@NotNull @Valid Hit> bm25,
      @NotNull @Size(max = 40) List<@NotNull @Valid Hit> fused,
      @NotNull Boolean degraded,
      String warning,
      @Valid ModelUsage usage) {}

  public record RerankRequest(
      @NotBlank @Size(max = 4000) String query,
      @NotNull @Size(max = 40) List<@NotNull @Valid Candidate> candidates,
      boolean allow_degraded,
      @Valid ModelConfiguration model_configuration) {}

  public record RerankResponse(
      @NotNull @Size(max = 40) List<@NotNull @Valid Hit> results,
      @NotNull Boolean degraded,
      String warning,
      @Valid ModelUsage usage) {}

  public record GenerationUsage(
      @Min(0) Long input_tokens, @Min(0) Long output_tokens, @Min(0) Long total_tokens) {}

  public record GenerateRequest(
      @NotBlank @Size(max = 4000) String query,
      @NotNull @Size(min = 1, max = 6) List<@NotNull @Valid Candidate> evidence,
      @Valid ModelConfiguration model_configuration) {}

  public record TokenizeRequest(
      @NotBlank @Size(max = 10000) String text,
      @Valid ModelConfiguration model_configuration,
      @Pattern(regexp = "cl100k_base|provider") String model_tokenizer,
      @Min(1) @Max(131072) Integer model_maximum) {
    public TokenizeRequest {
      model_tokenizer = model_tokenizer == null ? "cl100k_base" : model_tokenizer;
      model_maximum = model_maximum == null ? 600 : model_maximum;
    }
  }

  public record TokenizeResponse(
      @NotNull @Min(0) @Max(100000) Integer token_count,
      @NotNull @Min(0) @Max(100000) Integer model_token_count,
      @NotNull @Min(1) @Max(131072) Integer model_limit) {}

  public record ContextTokensRequest(
      @NotNull @Size(max = 40) List<@NotNull @Valid Candidate> candidates) {}

  public record TokenCount(
      @NotBlank @Size(max = 100) String id, @NotNull @Min(1) @Max(100000) Integer token_count) {}

  public record ContextTokensResponse(
      @NotNull @Size(max = 40) List<@NotNull @Valid TokenCount> counts,
      @NotNull @Pattern(regexp = "cl100k_base") String tokenizer) {}

  public record TaskClaim(
      String generation_id,
      String id,
      String lease_token,
      String kind,
      String tenant_id,
      String version_id,
      String filename,
      int pdf_page_limit,
      KnowledgeConfiguration.RuntimeConfiguration configuration) {}

  public record IndexChunk(String id, String content) {}

  public record ParsedChunk(
      @NotNull @Size(max = 100000) String source_text,
      @NotBlank @Size(max = 10000) String content,
      @NotBlank @Size(max = 20000) String location,
      @NotNull @Min(1) @Max(600) Integer token_count,
      @Min(0) @Max(49999) Integer context_ordinal) {}

  public record ParsedContext(
      @NotNull @Min(0) @Max(49999) Integer ordinal,
      @NotNull @Pattern(regexp = "PARENT|FAQ") String kind,
      @NotNull @Size(max = 100000) String source_text,
      @NotBlank @Size(max = 10000) String content,
      @NotBlank @Size(max = 20000) String location,
      @NotNull @Min(1) @Max(1600) Integer token_count,
      @Size(max = 1000) String question,
      @Size(max = 20) List<@NotBlank @Size(max = 400) String> alternatives,
      @Size(max = 8000) String answer) {}

  public record ParsedDocument(
      @NotNull @Size(min = 1, max = 50000) List<@NotNull @Valid ParsedChunk> chunks,
      @Size(max = 50000) List<@NotNull @Valid ParsedContext> contexts) {
    public ParsedDocument {
      contexts = contexts == null ? List.of() : List.copyOf(contexts);
    }
  }

  public record ManualFaqRequest(
      @NotBlank @Size(max = 1000) String question,
      @NotNull @Size(max = 20) List<@NotBlank @Size(max = 400) String> alternatives,
      @NotBlank @Size(max = 8000) String answer,
      @NotNull @Valid KnowledgeConfiguration.Chunking chunking,
      @NotNull @Valid ModelConfiguration model_configuration) {}

  public record TaskCompletion(
      @Size(max = 50000) List<@NotNull @Valid ParsedChunk> chunks,
      @Size(max = 50000) List<@NotNull @Valid ParsedContext> contexts,
      Boolean verified,
      @Size(max = 500) String model_identity,
      @Min(0) Long embedding_tokens,
      @Min(1) @Max(50000) Integer indexed_chunks,
      @Min(0) @Max(50000) Integer embedded_texts,
      @Min(0) @Max(50000) Integer reused_chunks,
      @Size(max = 36) String generation_id,
      @Pattern(regexp = "[0-9a-f]{64}") String manifest) {}

  public record TaskFailure(
      @NotBlank @Pattern(regexp = "[A-Z0-9_]{1,90}") String code, boolean retryable) {}

  public record IndexEntry(
      @NotBlank @Size(min = 36, max = 36) String id,
      @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String content_hash) {}

  public record IndexVerification(
      @NotBlank String tenant_id,
      @NotBlank String version_id,
      String generation_id,
      @NotBlank String expected_model_identity,
      @NotNull @Valid ModelConfiguration model_configuration,
      @NotNull @Size(max = 50000) List<@NotNull @Valid IndexEntry> chunks) {}

  public record IndexVerificationResponse(
      @NotNull Boolean consistent,
      @NotNull @Min(0) @Max(50000) Integer expected_count,
      @NotNull @Min(0) @Max(50000) Integer actual_count,
      @NotNull @Min(0) @Max(50000) Integer missing_count,
      @NotNull @Min(0) @Max(50000) Integer extra_count,
      @NotNull @Min(0) @Max(50000) Integer mismatched_count,
      @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String manifest,
      @NotNull java.util.Map<String, List<@Size(max = 36) String>> samples) {}

  public record CacheReference(
      @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String model_identity,
      @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String content_hash) {}

  public record Compaction(@NotBlank @Size(max = 255) String collection, @Min(1) long job_id) {}

  public record IndexPurge(
      @NotBlank String tenant_id, @NotBlank String version_id, String generation_id) {}

  public record CachePurge(
      @NotBlank String tenant_id,
      @NotNull @Size(max = 100) List<@NotNull @Valid CacheReference> entries) {}

  public record LegacyCachePurge(@NotBlank String tenant_id) {}

  public record PurgeResponse(
      @NotNull @AssertTrue Boolean verified,
      @NotNull @Size(max = 1000) List<@NotNull @Valid Compaction> compactions) {}

  public record CompactionRequest(
      @NotBlank String tenant_id,
      @NotNull @Size(max = 1000) List<@NotNull @Valid Compaction> compactions) {}

  public record CompactionResponse(@NotNull Boolean complete) {}
}
