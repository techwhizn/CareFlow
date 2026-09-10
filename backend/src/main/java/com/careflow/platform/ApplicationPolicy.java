package com.careflow.platform;

import jakarta.validation.constraints.*;

public final class ApplicationPolicy {
  private ApplicationPolicy() {}

  public record Models(
      @NotBlank @Size(max = 36) String rerank_profile_id,
      @Min(0) long rerank_profile_revision,
      @NotBlank @Size(max = 36) String generation_profile_id,
      @Min(0) long generation_profile_revision) {}

  public record Answer(
      @NotNull @Pattern(regexp = "auto|zh|en") String language,
      @NotNull @Pattern(regexp = "concise|standard|detailed") String style,
      @Min(128) @Max(2048) int maximum_output_tokens,
      @Min(0) @Max(6) int history_rounds,
      @Min(0) @Max(3000) int history_tokens) {
    public static Answer defaults() {
      return new Answer("auto", "standard", 2048, 6, 3000);
    }
  }

  public record Runtime(
      String id,
      KnowledgeConfiguration.Retrieval retrieval,
      KnowledgeConfiguration.RuntimeConfiguration models,
      Answer answer) {
    @Override
    public String toString() {
      return "ApplicationRuntime[redacted]";
    }
  }
}
