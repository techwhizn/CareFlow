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
      @NotBlank @Size(max = 4000) String query,
      @NotNull @Pattern(regexp = "hybrid|semantic|keyword") String mode,
      boolean allow_degraded,
      @Valid ModelConfiguration model_configuration,
      @Size(min = 1, max = 500) String expected_model_identity) {}

  public record RecallResponse(
      @NotNull @Size(max = 40) List<@NotNull @Valid Hit> dense,
      @NotNull @Size(max = 40) List<@NotNull @Valid Hit> bm25,
      @NotNull @Size(max = 40) List<@NotNull @Valid Hit> fused,
      @NotNull Boolean degraded,
      String warning) {}

  public record RerankRequest(
      @NotBlank @Size(max = 4000) String query,
      @NotNull @Size(max = 40) List<@NotNull @Valid Candidate> candidates,
      boolean allow_degraded,
      @Valid ModelConfiguration model_configuration) {}

  public record RerankResponse(
      @NotNull @Size(max = 40) List<@NotNull @Valid Hit> results,
      @NotNull Boolean degraded,
      String warning) {}

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

  public record TaskClaim(
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
      @NotNull @Min(1) @Max(600) Integer token_count) {}

  public record TaskCompletion(
      @Size(max = 50000) List<@NotNull @Valid ParsedChunk> chunks,
      Boolean verified,
      @Size(max = 500) String model_identity,
      @Min(0) Long embedding_tokens) {}

  public record TaskFailure(
      @NotBlank @Pattern(regexp = "[A-Z0-9_]{1,90}") String code, boolean retryable) {}
}
