package com.careflow.platform;

import static com.careflow.platform.Db.*;

import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Task-owned model call ledger. Call outcome may arrive after cancellation; it grants no lease. */
@Service
public class IndexAccountingService {
  private final Db db;
  private final KnowledgeConfigurationService configurations;

  public IndexAccountingService(Db db, KnowledgeConfigurationService configurations) {
    this.db = db;
    this.configurations = configurations;
  }

  public record Call(
      @NotNull @Pattern(regexp = "STARTED|SUCCEEDED|UNKNOWN") String state,
      @Min(1) @Max(32) int input_count,
      @Min(0) Long tokens) {}

  public void record(Map<String, Object> job, String lease, String callId, Call call) {
    UUID.fromString(callId);
    if (!str(job, "kind").equals("INDEX")) throw new IllegalArgumentException();
    var rows = db.list("SELECT * FROM processing_model_calls WHERE id=? FOR UPDATE", callId);
    if (call.state().equals("STARTED")) {
      if (call.tokens() != null) throw new IllegalArgumentException();
      if (!rows.isEmpty()) {
        verifyOwner(rows.getFirst(), job, lease, call.input_count());
        return;
      }
      var runtime = configurations.runtime(str(job, "tenant_id"), str(job, "configuration_id"));
      db.exec(
          "INSERT INTO processing_model_calls(id,tenant_id,job_id,lease_token,model_identity,input_count,state) VALUES(?,?,?,?,?,?,'STARTED')",
          callId,
          str(job, "tenant_id"),
          str(job, "id"),
          lease,
          configurations.modelIdentity(runtime.embedding()),
          call.input_count());
      return;
    }
    if (rows.isEmpty()) throw ApiException.hidden();
    var prior = rows.getFirst();
    verifyOwner(prior, job, lease, call.input_count());
    if (call.state().equals("UNKNOWN") && call.tokens() != null)
      throw new IllegalArgumentException();
    if (!str(prior, "state").equals("STARTED")) {
      if (!str(prior, "state").equals(call.state())
          || !Objects.equals(prior.get("tokens"), call.tokens())) throw ApiException.conflict();
      return;
    }
    db.exec(
        "UPDATE processing_model_calls SET state=?,tokens=?,completed_at=CURRENT_TIMESTAMP WHERE id=?",
        call.state(),
        call.tokens(),
        callId);
    if (call.tokens() != null)
      db.exec(
          "INSERT INTO usage_events(id,tenant_id,subject_id,request_key,resource_type,amount,state) VALUES(?,?,'system:worker',?,'EMBEDDING_TOKENS',?,'SETTLED')",
          id(),
          str(job, "tenant_id"),
          callId,
          call.tokens());
  }

  private void verifyOwner(
      Map<String, Object> call, Map<String, Object> job, String lease, int count) {
    if (!str(call, "tenant_id").equals(str(job, "tenant_id"))
        || !str(call, "job_id").equals(str(job, "id"))
        || !str(call, "lease_token").equals(lease)) throw ApiException.hidden();
    if (num(call, "input_count") != count) throw ApiException.conflict();
  }

  /** Invoked inside the same fenced transaction that changes the version to READY. */
  public void complete(Map<String, Object> job, String lease, Map<String, Object> body) {
    long indexed = counter(body, "indexed_chunks"),
        embedded = counter(body, "embedded_texts"),
        reused = counter(body, "reused_chunks");
    long expected =
        num(
            db.one(
                "SELECT COUNT(*) AS n FROM chunks WHERE tenant_id=? AND version_id=? AND enabled=TRUE",
                str(job, "tenant_id"),
                str(job, "version_id")),
            "n");
    if (expected == 0 || indexed != expected || embedded + reused != indexed)
      throw new IllegalArgumentException("Index counters mismatch");
    var calls =
        db.list(
            "SELECT state,input_count,tokens FROM processing_model_calls WHERE tenant_id=? AND job_id=? AND lease_token=?",
            str(job, "tenant_id"),
            str(job, "id"),
            lease);
    long inputs = 0;
    Long tokens = 0L;
    for (var call : calls) {
      if (!str(call, "state").equals("SUCCEEDED")) throw ApiException.conflict();
      inputs += num(call, "input_count");
      tokens =
          tokens == null || call.get("tokens") == null
              ? null
              : Math.addExact(tokens, num(call, "tokens"));
    }
    Object reported = body.get("embedding_tokens");
    if (inputs != embedded
        || (tokens == null
            ? reported != null
            : !(reported instanceof Number n) || n.longValue() != tokens))
      throw new IllegalArgumentException("Index usage mismatch");
    db.exec(
        "UPDATE jobs SET indexed_chunks=?,embedded_texts=?,reused_chunks=? WHERE id=?",
        indexed,
        embedded,
        reused,
        str(job, "id"));
  }

  private long counter(Map<String, Object> body, String field) {
    Object value = body.get(field);
    if (!(value instanceof Number n) || n.longValue() < 0 || n.longValue() > 50000)
      throw new IllegalArgumentException("Missing index counters");
    return n.longValue();
  }

  public Map<String, Object> summary(String tenant, String job) {
    var result =
        db.one(
            "SELECT COUNT(*) AS model_calls,COALESCE(SUM(tokens),0) AS known_embedding_tokens,COALESCE(SUM(CASE WHEN tokens IS NULL THEN 1 ELSE 0 END),0) AS unknown_usage_calls FROM processing_model_calls WHERE tenant_id=? AND job_id=?",
            tenant,
            job);
    // Known subtotal is explicitly labelled; unknown calls are never counted as zero usage.
    return result;
  }
}
