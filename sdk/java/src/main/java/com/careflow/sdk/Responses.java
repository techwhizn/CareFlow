package com.careflow.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * Additive response fields are tolerated. Nullable counts remain unknown, never coerced to zero.
 */
public final class Responses {
  private Responses() {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record KnowledgeBase(
      String id,
      String name,
      String description,
      String status,
      Long revision,
      Long configuration_revision,
      String owner_id,
      String language,
      List<String> tags) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Document(
      String id,
      String kb_id,
      String title,
      String status,
      String published_version,
      Long revision,
      Long metadata_revision) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record DocumentVersion(
      String id,
      String document_id,
      String filename,
      String state,
      Long revision,
      String configuration_id,
      String active_index_generation) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Chunk(
      String id,
      String version_id,
      String document_id,
      Long ordinal_no,
      String content,
      String source_text,
      String location,
      Long token_count,
      Boolean enabled,
      Long revision,
      List<String> covered_chunk_ids) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Job(
      String id,
      String version_id,
      String kind,
      String state,
      String error_code,
      String http_request_id,
      Map<String, Object> index_usage,
      Map<String, Object> ocr_usage) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record UploadResult(String document_id, String version_id, String job_id) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SearchResult(
      String query_record_id,
      String trace_id,
      List<Chunk> evidence,
      String evidence_status,
      Long evidence_tokens,
      Long evidence_token_limit,
      String evidence_tokenizer,
      String configuration_id,
      List<String> publication_versions,
      Long application_revision,
      String application_configuration_id,
      Boolean degraded) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Me(String tenant_id, String subject_id, String role, Map<String, String> tenant) {}
}
