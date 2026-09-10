package com.careflow.platform;

import static com.careflow.platform.Db.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class OcrAccountingTest extends ContentTestSupport {
  @Autowired OcrAccountingService ocr;
  @Autowired ModelUsageReadService usage;

  @Test
  void pageReceiptsAreIdempotentAndOldLeaseCannotStartNewPages() {
    String job = id(), lease = id(), page = id();
    db.exec(
        "INSERT INTO jobs(id,tenant_id,version_id,kind,state,request_key,lease_token,lease_until) VALUES(?,?,?,'PARSE','RUNNING',?,?,TIMESTAMPADD(SECOND,90,CURRENT_TIMESTAMP))",
        job,
        tenant,
        version,
        id(),
        lease);
    ocr.record(job, lease, page, new OcrAccountingService.Report("STARTED"));
    ocr.record(job, lease, page, new OcrAccountingService.Report("STARTED"));
    db.exec("UPDATE jobs SET lease_token=?,state='CANCELLED' WHERE id=?", id(), job);
    ocr.record(job, lease, page, new OcrAccountingService.Report("SUCCEEDED"));
    ocr.record(job, lease, page, new OcrAccountingService.Report("SUCCEEDED"));
    assertThatThrownBy(
            () -> ocr.record(job, lease, id(), new OcrAccountingService.Report("STARTED")))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () -> ocr.record(job, id(), page, new OcrAccountingService.Report("SUCCEEDED")))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () -> ocr.record(job, lease, page, new OcrAccountingService.Report("FAILED")))
        .isInstanceOf(ApiException.class);
    assertThat(usage.tenant(actor).toString())
        .contains("attempted_pages=1", "completed_pages=1", "unknown_size_objects=1");
  }
}
