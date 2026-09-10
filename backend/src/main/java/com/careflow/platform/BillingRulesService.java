package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Immutable price versions. Missing prices remain unconfigured, never implicitly free. */
@Service
public class BillingRulesService {
  static final Set<String> CUSTOMER = Set.of("QUERY", "PROCESSING_TASK");
  static final Set<String> PROVIDER =
      Set.of(
          "SOURCE_WRITE_BYTE",
          "OCR_PAGE",
          "EMBEDDING_TOKEN",
          "RERANK_TOKEN",
          "GENERATION_INPUT_TOKEN",
          "GENERATION_OUTPUT_TOKEN");
  private final Db db;
  private final Identity auth;
  private final ObjectMapper json;

  public BillingRulesService(Db db, Identity auth, ObjectMapper json) {
    this.db = db;
    this.auth = auth;
    this.json = json;
  }

  public record Input(
      @NotBlank @Size(max = 200) String name,
      @NotNull @Pattern(regexp = "CNY|USD") String currency,
      @NotNull Map<String, BigDecimal> customer_rates,
      @NotNull Map<String, BigDecimal> provider_rates) {}

  public record Activate(UUID rule_id, @Min(0) long revision) {}

  private void validate(Map<String, BigDecimal> rates, Set<String> names) {
    if (rates == null || !names.containsAll(rates.keySet())) throw new IllegalArgumentException();
    for (var rate : rates.values())
      if (rate == null
          || rate.signum() < 0
          || rate.compareTo(new BigDecimal("1000000")) > 0
          || rate.scale() > 12) throw new IllegalArgumentException("Invalid unit price");
  }

  @Transactional
  public Object create(Actor actor, Input input) {
    auth.lock(actor);
    auth.admin(actor);
    validate(input.customer_rates(), CUSTOMER);
    validate(input.provider_rates(), PROVIDER);
    if (input.currency() == null || !Set.of("CNY", "USD").contains(input.currency()))
      throw new IllegalArgumentException();
    String id = id();
    try {
      db.exec(
          "INSERT INTO billing_rules(id,tenant_id,name,currency,customer_rates,provider_rates,created_by) VALUES(?,?,?,?,?,?,?)",
          id,
          actor.tenant(),
          input.name(),
          input.currency(),
          json.writeValueAsString(input.customer_rates()),
          json.writeValueAsString(input.provider_rates()),
          actor.subject());
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException();
    }
    auth.audit(actor, "BILLING_RULE_CREATE", id, "");
    return rule(actor.tenant(), id);
  }

  public Object list(Actor actor) {
    auth.admin(actor);
    var current =
        db.one(
            "SELECT billing_rule_id,billing_revision AS revision FROM tenants WHERE id=?",
            actor.tenant());
    current.put(
        "rules",
        db
            .list(
                "SELECT id FROM billing_rules WHERE tenant_id=? ORDER BY created_at DESC,id DESC",
                actor.tenant())
            .stream()
            .map(r -> rule(actor.tenant(), str(r, "id")))
            .toList());
    return current;
  }

  @Transactional
  public Object activate(Actor actor, Activate input) {
    auth.lock(actor);
    auth.admin(actor);
    if (input.rule_id() != null) rule(actor.tenant(), input.rule_id().toString());
    var before = db.one("SELECT billing_rule_id FROM tenants WHERE id=?", actor.tenant());
    if (db.exec(
            "UPDATE tenants SET billing_rule_id=?,billing_revision=billing_revision+1 WHERE id=? AND billing_revision=?",
            input.rule_id() == null ? null : input.rule_id().toString(),
            actor.tenant(),
            input.revision())
        != 1) throw ApiException.conflict();
    auth.audit(
        actor,
        "BILLING_RULE_ACTIVATE",
        Objects.toString(input.rule_id(), ""),
        "previous=" + str(before, "billing_rule_id"));
    return list(actor);
  }

  public Map<String, Object> snapshot(String tenant) {
    return db.one("SELECT billing_rule_id,billing_revision FROM tenants WHERE id=?", tenant);
  }

  public String current(String tenant) {
    return str(snapshot(tenant), "billing_rule_id");
  }

  public void checkRevision(String tenant, Long expected) {
    if (expected != null && (expected < 0 || num(snapshot(tenant), "billing_revision") != expected))
      throw new ApiException(409, "BILLING_RULE_CHANGED", "费率已改变，请重新预估后再导入");
  }

  public Map<String, Object> rule(String tenant, String rule) {
    if (rule == null || rule.isBlank()) return Map.of();
    var row =
        db.one(
            "SELECT id,name,currency,customer_rates,provider_rates,created_at FROM billing_rules WHERE tenant_id=? AND id=?",
            tenant,
            rule);
    for (String side : List.of("customer_rates", "provider_rates"))
      try {
        row.put(
            side, json.readValue(str(row, side), new TypeReference<Map<String, BigDecimal>>() {}));
      } catch (Exception e) {
        throw new IllegalStateException("Invalid stored billing rule", e);
      }
    return row;
  }

  public void attachJob(String tenant, String job, long sourceBytes) {
    db.exec(
        "UPDATE jobs SET billing_rule_id=NULLIF(?,''),source_write_bytes=? WHERE tenant_id=? AND id=?",
        current(tenant),
        sourceBytes,
        tenant,
        job);
  }

  public void attachRequest(String tenant, String request) {
    db.exec(
        "UPDATE usage_events SET billing_rule_id=NULLIF(?,'') WHERE tenant_id=? AND id=?",
        current(tenant),
        tenant,
        request);
  }
}
