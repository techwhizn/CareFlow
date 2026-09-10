package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CostAccountingService {
  private final Db db;
  private final Identity auth;
  private final BillingRulesService rules;
  private final RequestLogService logs;

  public CostAccountingService(
      Db db, Identity auth, BillingRulesService rules, RequestLogService logs) {
    this.db = db;
    this.auth = auth;
    this.rules = rules;
    this.logs = logs;
  }

  @SuppressWarnings("unchecked")
  public Object request(Actor actor, String app, UUID request) {
    logs.detail(actor, app, request);
    var event =
        db.one(
            "SELECT state,billing_rule_id FROM usage_events WHERE tenant_id=? AND id=?",
            actor.tenant(),
            request.toString());
    var provider = new LinkedHashMap<String, CostCalculation.Quantity>();
    for (String stage : List.of("EMBEDDING", "RERANK")) {
      var usage =
          db.one(
              "SELECT COALESCE(SUM(total_tokens),0) AS known,COALESCE(SUM(CASE WHEN usage_state IN ('UNKNOWN','NOT_REPORTED') THEN 1 ELSE 0 END),0) AS unknown_calls FROM retrieval_model_calls WHERE tenant_id=? AND request_id=? AND stage=?",
              actor.tenant(),
              request.toString(),
              stage);
      provider.put(
          stage + "_TOKEN",
          new CostCalculation.Quantity(num(usage, "known"), num(usage, "unknown_calls") > 0));
    }
    var generation =
        db.list(
            "SELECT input_tokens,output_tokens,usage_state FROM generation_usage WHERE tenant_id=? AND request_id=?",
            actor.tenant(),
            request.toString());
    for (String field : List.of("input_tokens", "output_tokens")) {
      Long units = 0L;
      boolean uncertain = false;
      if (!generation.isEmpty()
          && !str(generation.getFirst(), "usage_state").equals("NOT_CALLED")) {
        Object value = generation.getFirst().get(field);
        units = value == null ? null : ((Number) value).longValue();
        uncertain = value == null;
      }
      provider.put(
          field.equals("input_tokens") ? "GENERATION_INPUT_TOKEN" : "GENERATION_OUTPUT_TOKEN",
          new CostCalculation.Quantity(units, uncertain));
    }
    return quote(
        actor,
        str(event, "billing_rule_id"),
        Map.of(
            "QUERY", CostCalculation.Quantity.known(str(event, "state").equals("SETTLED") ? 1 : 0)),
        provider,
        str(event, "state").equals("RESERVED") ? "PENDING" : "ACTUAL");
  }

  public Object job(Actor actor, UUID id) {
    var job =
        db.one("SELECT * FROM jobs WHERE tenant_id=? AND id=?", actor.tenant(), id.toString());
    auth.version(actor, str(job, "version_id"), "read");
    var embedding =
        db.one(
            "SELECT COALESCE(SUM(tokens),0) AS known,COALESCE(SUM(CASE WHEN tokens IS NULL THEN 1 ELSE 0 END),0) AS uncertain FROM processing_model_calls WHERE tenant_id=? AND job_id=?",
            actor.tenant(),
            id.toString());
    var ocr =
        db.one(
            "SELECT COALESCE(SUM(CASE WHEN state='SUCCEEDED' THEN 1 ELSE 0 END),0) AS known,COALESCE(SUM(CASE WHEN state<>'SUCCEEDED' THEN 1 ELSE 0 END),0) AS uncertain FROM ocr_page_calls WHERE tenant_id=? AND job_id=?",
            actor.tenant(),
            id.toString());
    return quote(
        actor,
        str(job, "billing_rule_id"),
        Map.of(
            "PROCESSING_TASK", CostCalculation.Quantity.known(bool(job, "quota_counted") ? 1 : 0)),
        Map.of(
            "SOURCE_WRITE_BYTE", CostCalculation.Quantity.known(num(job, "source_write_bytes")),
            "EMBEDDING_TOKEN",
                new CostCalculation.Quantity(
                    num(embedding, "known"), num(embedding, "uncertain") > 0),
            "OCR_PAGE", new CostCalculation.Quantity(num(ocr, "known"), num(ocr, "uncertain") > 0)),
        Set.of("DONE", "FAILED", "CANCELLED").contains(str(job, "state")) ? "ACTUAL" : "PENDING");
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> quote(
      Actor actor,
      String ruleId,
      Map<String, CostCalculation.Quantity> customer,
      Map<String, CostCalculation.Quantity> provider,
      String basis) {
    var rule = rules.rule(actor.tenant(), ruleId);
    var response = new LinkedHashMap<String, Object>();
    response.put("rule_id", ruleId.isBlank() ? null : ruleId);
    response.put("currency", rule.get("currency"));
    response.put("basis", basis);
    response.put(
        "customer",
        CostCalculation.calculate(
            (Map<String, BigDecimal>) rule.getOrDefault("customer_rates", Map.of()),
            customer,
            !rule.isEmpty()));
    if (!actor.app() && Set.of("OWNER", "ADMIN").contains(actor.role()))
      response.put(
          "provider",
          CostCalculation.calculate(
              (Map<String, BigDecimal>) rule.getOrDefault("provider_rates", Map.of()),
              provider,
              !rule.isEmpty()));
    return response;
  }
}
