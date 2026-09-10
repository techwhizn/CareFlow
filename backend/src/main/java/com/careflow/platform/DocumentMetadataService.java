package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentMetadataService {
  public record Metadata(
      @NotBlank @Size(max = 250) String title,
      @NotNull @Size(max = 2000) String source,
      @NotNull @Size(max = 20) @Pattern(regexp = "[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*")
          String language,
      @NotNull @Size(max = 20) List<@NotBlank @Size(max = 50) String> tags,
      @NotNull @Size(max = 50) List<@NotBlank @Size(max = 100) String> product_models,
      OffsetDateTime valid_from,
      OffsetDateTime valid_until,
      @Min(0) long revision) {}

  private final DocumentMetadataRepository repository;
  private final Identity auth;
  private final ObjectMapper json;

  public DocumentMetadataService(
      DocumentMetadataRepository repository, Identity auth, ObjectMapper json) {
    this.repository = repository;
    this.auth = auth;
    this.json = json;
  }

  public Metadata get(Actor actor, String id) {
    return view(auth.document(actor, id, "read"));
  }

  private List<String> strings(Map<String, Object> row, String field) {
    try {
      return row.get(field) == null
          ? List.of()
          : json.readValue(str(row, field), new TypeReference<List<String>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("Invalid document metadata");
    }
  }

  private OffsetDateTime time(Map<String, Object> row, String field) {
    return row.get(field) == null
        ? null
        : (row.get(field) instanceof java.time.LocalDateTime local
                ? local
                : ((Timestamp) row.get(field)).toLocalDateTime())
            .atOffset(ZoneOffset.UTC);
  }

  private Metadata view(Map<String, Object> row) {
    return new Metadata(
        str(row, "title"),
        str(row, "source"),
        str(row, "language"),
        strings(row, "tags_json"),
        strings(row, "product_models_json"),
        time(row, "valid_from"),
        time(row, "valid_until"),
        num(row, "revision"));
  }

  @Transactional
  public Map<String, Object> update(Actor actor, String id, Metadata input) {
    auth.lock(actor);
    var before = auth.document(actor, id, "edit");
    if (input.valid_from() != null
        && input.valid_until() != null
        && !input.valid_from().isBefore(input.valid_until()))
      throw new ApiException(400, "INVALID_VALIDITY_PERIOD", "失效时间必须晚于生效时间");
    // Validate against MySQL DATETIME before persistence, using the normalized UTC year.
    for (var instant :
        List.of(
            Optional.ofNullable(input.valid_from()), Optional.ofNullable(input.valid_until()))) {
      if (instant.isPresent()) {
        int year = instant.get().withOffsetSameInstant(ZoneOffset.UTC).getYear();
        if (year < 1000 || year > 9999)
          throw new ApiException(400, "INVALID_VALIDITY_PERIOD", "时间年份须在1000至9999范围内");
      }
    }
    try {
      String tags =
          json.writeValueAsString(input.tags().stream().map(String::trim).distinct().toList());
      String models =
          json.writeValueAsString(
              input.product_models().stream().map(String::trim).distinct().toList());
      if (repository.update(actor.tenant(), id, input, tags, models) != 1)
        throw ApiException.conflict();
      repository.history(
          actor.tenant(),
          id,
          input.revision() + 1,
          actor.subject(),
          json.writeValueAsString(
              Map.of(
                  "before",
                  view(before),
                  "after",
                  new Metadata(
                      input.title().trim(),
                      input.source(),
                      input.language(),
                      input.tags().stream().map(String::trim).distinct().toList(),
                      input.product_models().stream().map(String::trim).distinct().toList(),
                      input.valid_from(),
                      input.valid_until(),
                      input.revision() + 1))));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException();
    }
    auth.audit(actor, "DOCUMENT_METADATA_UPDATE", id, "revision=" + (input.revision() + 1));
    return Map.of("id", id, "revision", input.revision() + 1);
  }

  public List<Map<String, Object>> history(Actor actor, String id, int page) {
    auth.document(actor, id, "edit");
    if (page < 0 || page > 10000) throw new IllegalArgumentException();
    return repository.history(actor.tenant(), id, page);
  }
}
