package com.careflow.platform;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/** Public configuration contains profile references, never credentials. */
public final class KnowledgeConfiguration {
  private KnowledgeConfiguration() {}

  @Schema(name = "KnowledgeParsing")
  public record Parsing(@Min(1) @Max(500) int pdf_page_limit) {}

  @Schema(name = "KnowledgeChunking")
  public record Chunking(
      @Min(1) @Max(600) int target,
      @Min(1) @Max(600) int maximum,
      @Min(0) @Max(599) int overlap,
      @Pattern(regexp = "recursive|token") String strategy,
      Boolean include_context,
      @Pattern(regexp = "cl100k_base|provider") String model_tokenizer,
      @Min(1) @Max(131072) Integer model_maximum,
      @Pattern(regexp = "standard|parent_child|faq") String layout,
      @Min(1) @Max(1600) Integer parent_maximum) {
    public Chunking {
      strategy = strategy == null ? "recursive" : strategy;
      include_context = include_context == null ? true : include_context;
      model_tokenizer = model_tokenizer == null ? "cl100k_base" : model_tokenizer;
      model_maximum = model_maximum == null ? 600 : model_maximum;
      layout = layout == null ? "standard" : layout;
      parent_maximum = parent_maximum == null ? 1600 : parent_maximum;
      if (!(0 <= overlap && overlap < target && target <= maximum && maximum <= 600))
        throw new IllegalArgumentException("Expected overlap < target <= maximum <= 600");
    }

    public Chunking(int target, int maximum, int overlap) {
      this(target, maximum, overlap, "recursive", true, "cl100k_base", 600, "standard", 1600);
    }

    public Chunking(
        int target,
        int maximum,
        int overlap,
        String strategy,
        Boolean context,
        String tokenizer,
        Integer modelMaximum) {
      this(target, maximum, overlap, strategy, context, tokenizer, modelMaximum, "standard", 1600);
    }
  }

  @Schema(name = "KnowledgeRetrieval")
  public record Retrieval(
      @NotNull @Pattern(regexp = "hybrid|semantic|keyword") String mode,
      @Min(1) @Max(6) int limit,
      Double minimum_rerank_score,
      boolean allow_degraded) {
    public Retrieval {
      if (minimum_rerank_score != null && !Double.isFinite(minimum_rerank_score))
        throw new IllegalArgumentException("Finite score required");
    }
  }

  @Schema(name = "KnowledgeModels")
  public record Models(
      @NotBlank @Size(max = 36) String embedding_profile_id,
      @NotBlank @Size(max = 36) String rerank_profile_id,
      @NotBlank @Size(max = 36) String generation_profile_id,
      @Min(0) long embedding_profile_revision,
      @Min(0) long rerank_profile_revision,
      @Min(0) long generation_profile_revision) {
    public Models(String embedding, String rerank, String generation) {
      this(embedding, rerank, generation, 0, 0, 0);
    }
  }

  @Schema(name = "KnowledgeDefinition")
  public record Definition(
      @NotBlank @Size(max = 200) String name,
      @NotNull @Valid Parsing parsing,
      @NotNull @Valid Chunking chunking,
      @NotNull @Valid Retrieval retrieval,
      @NotNull @Valid Models models) {}

  @Schema(name = "KnowledgePublish")
  public record Publish(
      @NotBlank @Size(max = 36) String configuration_id,
      @Min(0) long revision,
      @NotBlank @Size(max = 1000) String reason) {}

  @Schema(name = "KnowledgeRuntimeConfiguration")
  public record RuntimeConfiguration(
      String id,
      Parsing parsing,
      Chunking chunking,
      WorkerProtocolV1.ModelConfiguration embedding,
      WorkerProtocolV1.ModelConfiguration rerank,
      WorkerProtocolV1.ModelConfiguration generation) {
    @Override
    public String toString() {
      return "RuntimeConfiguration[redacted]";
    }
  }
}
