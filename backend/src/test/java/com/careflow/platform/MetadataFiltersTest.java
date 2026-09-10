package com.careflow.platform;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

class MetadataFiltersTest {
  ObjectMapper json = new ObjectMapper();
  MetadataFilters filters = new MetadataFilters(json);

  MetadataFilters.Rule rule(
      MetadataFilters.Field field, MetadataFilters.Operator op, Object value) {
    return new MetadataFilters.Rule(field, op, json.valueToTree(value));
  }

  @Test
  void exactArrayMembershipAndAndSemanticsDoNotInterpretExpressions() {
    var row =
        Map.<String, Object>of(
            "language",
            "zh",
            "product_models_json",
            "[\"CF-100\",\"CF-200\"]",
            "title",
            "O'Reilly % _ guide");
    assertThat(
            filters
                .compile(
                    List.of(
                        rule(MetadataFilters.Field.language, MetadataFilters.Operator.eq, "zh"),
                        rule(
                            MetadataFilters.Field.product_models,
                            MetadataFilters.Operator.in,
                            List.of("CF-100"))))
                .test(row))
        .isTrue();
    assertThat(
            filters
                .compile(
                    List.of(
                        rule(
                            MetadataFilters.Field.product_models,
                            MetadataFilters.Operator.contains,
                            "CF-1")))
                .test(row))
        .isFalse();
    assertThat(
            filters
                .compile(
                    List.of(
                        rule(
                            MetadataFilters.Field.language,
                            MetadataFilters.Operator.eq,
                            "zh' OR 1=1 --")))
                .test(row))
        .isFalse();
    assertThat(
            filters
                .compile(
                    List.of(
                        rule(
                            MetadataFilters.Field.title, MetadataFilters.Operator.contains, "% _")))
                .test(row))
        .isTrue();
  }

  @Test
  void timesCompareInstantsAndUnboundedMetadataDoesNotMatchDatePredicates() {
    var predicate =
        filters.compile(
            List.of(
                rule(
                    MetadataFilters.Field.valid_from,
                    MetadataFilters.Operator.eq,
                    "2026-09-10T08:00:00+08:00")));
    assertThat(predicate.test(Map.of("valid_from", LocalDateTime.of(2026, 9, 10, 0, 0)))).isTrue();
    assertThat(predicate.test(Map.of())).isFalse();
    assertThatThrownBy(
            () ->
                filters.compile(
                    List.of(
                        rule(
                            MetadataFilters.Field.valid_from,
                            MetadataFilters.Operator.gte,
                            "2026-09-10"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsWrongTypesOperatorsAndUnboundedListsBeforeReadingDocuments() {
    assertThatThrownBy(
            () ->
                filters.compile(
                    List.of(rule(MetadataFilters.Field.language, MetadataFilters.Operator.eq, 10))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                filters.compile(
                    List.of(rule(MetadataFilters.Field.tags, MetadataFilters.Operator.eq, "tag"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                filters.compile(
                    List.of(
                        rule(
                            MetadataFilters.Field.language,
                            MetadataFilters.Operator.in,
                            List.of()))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                filters.compile(
                    Collections.nCopies(
                        11,
                        rule(MetadataFilters.Field.language, MetadataFilters.Operator.eq, "zh"))))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
