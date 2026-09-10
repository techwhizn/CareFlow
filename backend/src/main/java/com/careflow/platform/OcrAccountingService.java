package com.careflow.platform;

import static com.careflow.platform.Db.*;

import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One receipt per attempted OCR page, fenced to the immutable task lease. */
@Service
public class OcrAccountingService {
  private final Db db;
  private final Tasks tasks;

  public OcrAccountingService(Db db, Tasks tasks) {
    this.db = db;
    this.tasks = tasks;
  }

  public record Report(@Pattern(regexp = "STARTED|SUCCEEDED|FAILED") String state) {}

  @Transactional
  public void record(String jobId, String lease, String callId, Report report) {
    UUID.fromString(callId);
    if (report.state() == null
        || !java.util.Set.of("STARTED", "SUCCEEDED", "FAILED").contains(report.state()))
      throw new IllegalArgumentException();
    var tenant = db.one("SELECT tenant_id FROM jobs WHERE id=?", jobId);
    db.one("SELECT id FROM tenants WHERE id=? FOR UPDATE", str(tenant, "tenant_id"));
    var previous = db.list("SELECT * FROM ocr_page_calls WHERE id=?", callId);
    if (!previous.isEmpty()) {
      var call = previous.getFirst();
      if (!str(call, "job_id").equals(jobId) || !str(call, "lease_token").equals(lease))
        throw ApiException.hidden();
      if (str(call, "state").equals(report.state())) return;
      if (!str(call, "state").equals("STARTED") || report.state().equals("STARTED"))
        throw ApiException.conflict();
      db.exec(
          "UPDATE ocr_page_calls SET state=?,completed_at=CURRENT_TIMESTAMP WHERE id=? AND state='STARTED'",
          report.state(),
          callId);
      return;
    }
    if (!report.state().equals("STARTED")) throw ApiException.hidden();
    var job = tasks.validate(jobId, lease);
    if (!str(job, "kind").equals("PARSE")) throw ApiException.hidden();
    db.exec(
        "INSERT INTO ocr_page_calls(id,tenant_id,job_id,lease_token,state) VALUES(?,?,?,?,'STARTED')",
        callId,
        str(job, "tenant_id"),
        jobId,
        lease);
  }

  public Object summary(String tenant, String job) {
    String filter = job == null ? "" : " AND job_id=?";
    Object[] args = job == null ? new Object[] {tenant} : new Object[] {tenant, job};
    return db.one(
        "SELECT COUNT(*) AS attempted_pages,COALESCE(SUM(CASE WHEN state='SUCCEEDED' THEN 1 ELSE 0 END),0) AS completed_pages,COALESCE(SUM(CASE WHEN state='FAILED' THEN 1 ELSE 0 END),0) AS failed_pages,COALESCE(SUM(CASE WHEN state='STARTED' THEN 1 ELSE 0 END),0) AS uncertain_pages FROM ocr_page_calls WHERE tenant_id=?"
            + filter,
        args);
  }
}
