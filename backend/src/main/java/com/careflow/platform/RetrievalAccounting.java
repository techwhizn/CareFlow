package com.careflow.platform;

import static com.careflow.platform.Db.*;

import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** Durable supplier usage, independent of client quota settlement. Never stores query text. */
@Service
public class RetrievalAccounting {
  private final Db db;

  public RetrievalAccounting(Db db) {
    this.db = db;
  }

  public Map<String, Object> call(
      String tenant,
      String request,
      String stage,
      String configuration,
      int inputCount,
      Supplier<Map<String, Object>> operation) {
    if (request == null) return operation.get();
    if (!Set.of("EMBEDDING", "RERANK").contains(stage) || inputCount < 0)
      throw new IllegalArgumentException();
    String call = id();
    if (db.exec(
            "INSERT INTO retrieval_model_calls(id,tenant_id,request_id,stage,configuration_id,input_count,call_state,usage_state) SELECT ?,tenant_id,id,?,?,?,'STARTED',? FROM usage_events WHERE tenant_id=? AND id=? AND state='RESERVED'",
            call,
            stage,
            configuration,
            inputCount,
            inputCount == 0 ? "NOT_CALLED" : "UNKNOWN",
            tenant,
            request)
        != 1) throw new IllegalStateException("Missing query reservation");
    try {
      var result = operation.get();
      Object raw = result.get("usage");
      Map<?, ?> usage = raw instanceof Map<?, ?> m ? m : Map.of();
      String state = inputCount == 0 ? "NOT_CALLED" : String.valueOf(usage.get("state"));
      if (!Set.of("REPORTED", "NOT_REPORTED", "UNKNOWN", "NOT_CALLED").contains(state))
        state = "UNKNOWN";
      Object value = usage.get("total_tokens");
      Long tokens =
          value instanceof Integer || value instanceof Long ? ((Number) value).longValue() : null;
      if (tokens != null && tokens < 0)
        throw new IllegalArgumentException("Negative reported usage");
      if (!"REPORTED".equals(state)) tokens = null;
      if ("REPORTED".equals(state) && tokens == null) state = "UNKNOWN";
      db.exec(
          "UPDATE retrieval_model_calls SET call_state='SUCCEEDED',usage_state=?,total_tokens=?,completed_at=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=? AND call_state='STARTED'",
          state,
          tokens,
          call,
          tenant);
      return result;
    } catch (RuntimeException error) {
      db.exec(
          "UPDATE retrieval_model_calls SET call_state='FAILED',completed_at=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=? AND call_state='STARTED'",
          call,
          tenant);
      throw error;
    }
  }
}
