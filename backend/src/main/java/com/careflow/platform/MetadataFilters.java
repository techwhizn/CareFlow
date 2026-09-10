package com.careflow.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

/** Compiles a closed filter language to predicates, never SQL or vector expressions. */
@Component
public class MetadataFilters {
  public enum Field {
    title,
    source,
    language,
    tags,
    product_models,
    valid_from,
    valid_until
  }

  public enum Operator {
    eq,
    in,
    contains,
    gte,
    lte
  }

  public record Rule(Field field, Operator operator, JsonNode value) {}

  private final ObjectMapper json;

  public MetadataFilters(ObjectMapper json) {
    this.json = json;
  }

  public Predicate<Map<String, Object>> compile(List<Rule> rules) {
    if (rules == null || rules.isEmpty()) return row -> true;
    if (rules.size() > 10) throw new IllegalArgumentException("At most 10 metadata filters");
    var compiled = rules.stream().map(this::compile).toList();
    return row -> compiled.stream().allMatch(predicate -> predicate.test(row));
  }

  private String text(JsonNode value) {
    if (value == null
        || !value.isTextual()
        || value.textValue().isBlank()
        || value.textValue().length() > 2000)
      throw new IllegalArgumentException("Expected nonempty filter string");
    return value.textValue();
  }

  private Predicate<Map<String, Object>> compile(Rule rule) {
    if (rule == null || rule.field() == null || rule.operator() == null || rule.value() == null)
      throw new IllegalArgumentException("Incomplete metadata filter");
    String field = rule.field().name();
    Operator op = rule.operator();
    if (Set.of(Field.valid_from, Field.valid_until).contains(rule.field())) {
      if (!Set.of(Operator.eq, Operator.gte, Operator.lte).contains(op))
        throw new IllegalArgumentException("Unsupported time operator");
      Instant expected;
      try {
        expected = OffsetDateTime.parse(text(rule.value())).toInstant();
      } catch (DateTimeException invalid) {
        throw new IllegalArgumentException("Expected time with offset");
      }
      return row -> {
        Object raw = row.get(field);
        if (raw == null) return false;
        Instant actual =
            (raw instanceof LocalDateTime local ? local : ((Timestamp) raw).toLocalDateTime())
                .toInstant(ZoneOffset.UTC);
        int comparison = actual.compareTo(expected);
        return op == Operator.eq
            ? comparison == 0
            : op == Operator.gte ? comparison >= 0 : comparison <= 0;
      };
    }
    boolean collection = Set.of(Field.tags, Field.product_models).contains(rule.field());
    boolean supported =
        collection
            ? Set.of(Operator.contains, Operator.in).contains(op)
            : Set.of(Operator.eq, Operator.in, Operator.contains).contains(op);
    if (!supported) throw new IllegalArgumentException("Unsupported metadata operator");
    Set<String> values = new HashSet<>();
    if (op == Operator.in) {
      if (!rule.value().isArray() || rule.value().isEmpty() || rule.value().size() > 20)
        throw new IllegalArgumentException("Expected 1 to 20 strings");
      rule.value().forEach(value -> values.add(text(value)));
    } else values.add(text(rule.value()));
    return row -> {
      if (collection)
        return storedList(row.get(field + "_json")).stream().anyMatch(values::contains);
      Object raw = row.get(field);
      if (raw == null) return false;
      String value = raw.toString();
      return op == Operator.contains
          ? value.contains(values.iterator().next())
          : values.contains(value);
    };
  }

  private List<String> storedList(Object raw) {
    if (raw == null) return List.of();
    try {
      JsonNode value = json.readTree(raw.toString());
      if (!value.isArray()) throw new IllegalStateException("Invalid stored metadata");
      var values = new ArrayList<String>();
      for (var item : value) {
        if (!item.isTextual()) throw new IllegalStateException("Invalid stored metadata");
        values.add(item.textValue());
      }
      return values;
    } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
      throw new IllegalStateException("Invalid stored metadata");
    }
  }
}
