package com.careflow.platform;

import static org.assertj.core.api.Assertions.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:fairness;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "careflow.scheduling=false",
      "careflow.model-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "careflow.internal-token=internal-testing-secret-at-least-32-chars",
      "careflow.bootstrap-token=bootstrap-testing-secret-at-least-32-chars"
    })
class FairTaskDispatcherTest {
  @Autowired Db db;
  @Autowired FairTaskDispatcher dispatcher;
  @Autowired Tasks tasks;
  @MockitoBean BlobStore blobs;
  @MockitoBean WorkerClient worker;

  @BeforeEach
  void clean() {
    for (String table :
        List.of("outbox", "jobs", "document_versions", "documents", "knowledge_bases", "tenants"))
      db.exec("DELETE FROM " + table);
    db.exec("UPDATE task_dispatch_cursor SET tenant_id='' WHERE id=1");
  }

  String tenant(int number, int jobs) {
    String tenant = String.format("tenant-%03d", number),
        kb = Db.id(),
        doc = Db.id(),
        version = Db.id();
    db.exec("INSERT INTO tenants(id,name) VALUES(?,?)", tenant, "Synthetic");
    db.exec(
        "INSERT INTO knowledge_bases(id,tenant_id,name,description,owner_id) VALUES(?,?,'Synthetic','','owner')",
        kb,
        tenant);
    db.exec(
        "INSERT INTO documents(id,tenant_id,kb_id,title) VALUES(?,?,?,'Synthetic')",
        doc,
        tenant,
        kb);
    db.exec(
        "INSERT INTO document_versions(id,tenant_id,document_id,object_key,filename,digest) VALUES(?,?,?,'synthetic','fixture.txt','digest')",
        version,
        tenant,
        doc);
    for (int i = 0; i < jobs; i++) {
      String job = Db.id();
      db.exec(
          "INSERT INTO jobs(id,tenant_id,version_id,kind,request_key) VALUES(?,?,?,'PARSE',?)",
          job,
          tenant,
          version,
          Db.id());
      db.exec("INSERT INTO outbox(id,job_id) VALUES(?,?)", Db.id(), job);
    }
    return tenant;
  }

  @Test
  void alreadyCountedRetryCanProceedWhenNewTaskQuotaIsExhausted() {
    String tenant = tenant(0, 2);
    var jobs = db.list("SELECT id FROM jobs WHERE tenant_id=? ORDER BY created_at,id", tenant);
    String retry = Db.str(jobs.getLast(), "id");
    db.exec("UPDATE jobs SET quota_counted=TRUE WHERE id=?", retry);
    db.exec("UPDATE tenants SET processing_used=1,processing_limit=1 WHERE id=?", tenant);
    assertThat(dispatcher.reserveNext().job()).isEqualTo(retry);
  }

  @Test
  void highLoadTenantDoesNotFillNotificationsAheadOfOtherTenants() {
    tenant(0, 100);
    for (int i = 1; i < 10; i++) tenant(i, 1);
    var served = new HashSet<String>();
    for (int i = 0; i < 10; i++) {
      var next = dispatcher.reserveNext();
      assertThat(next.job()).isNotNull();
      served.add(next.tenant());
    }
    assertThat(served).hasSize(10);
    assertThat(
            Db.num(
                db.one(
                    "SELECT COUNT(*) AS n FROM jobs WHERE tenant_id='tenant-000' AND dispatch_until IS NOT NULL"),
                "n"))
        .isEqualTo(1);
    assertThat(Db.num(db.one("SELECT SUM(processing_used) AS n FROM tenants"), "n")).isZero();
  }

  @Test
  void pausedTenantDoesNotBlockOthersAndResumesWithoutAttemptCharge() {
    String paused = tenant(0, 2);
    tenant(1, 1);
    db.exec("UPDATE tenants SET entitlement_active=FALSE WHERE id=?", paused);
    var waiting = dispatcher.reserveNext();
    assertThat(waiting.job()).isNull();
    var other = dispatcher.reserveNext();
    assertThat(other.tenant()).isEqualTo("tenant-001");
    assertThat(other.job()).isNotNull();
    assertThat(
            db.list(
                "SELECT id FROM jobs WHERE tenant_id=? AND wait_reason='ENTITLEMENT_INACTIVE'",
                paused))
        .hasSize(1);
    db.exec("UPDATE tenants SET entitlement_active=TRUE WHERE id=?", paused);
    var resumed = dispatcher.reserveNext();
    assertThat(resumed.tenant()).isEqualTo(paused);
    assertThat(resumed.job()).isNotNull();
    assertThat(Db.num(db.one("SELECT SUM(attempts) AS n FROM jobs"), "n")).isZero();
  }

  @Test
  void concurrentDispatchersReserveOnlyOneNotificationAndHonorRunningLimit() throws Exception {
    String tenant = tenant(0, 20);
    db.exec("UPDATE tenants SET task_concurrency_limit=1 WHERE id=?", tenant);
    var selected = new ArrayList<FairTaskDispatcher.Selection>();
    try (var pool = Executors.newFixedThreadPool(8)) {
      var futures = new ArrayList<Future<FairTaskDispatcher.Selection>>();
      for (int i = 0; i < 8; i++) futures.add(pool.submit(dispatcher::reserveNext));
      for (var future : futures) {
        var next = future.get();
        if (next != null && next.job() != null) selected.add(next);
      }
    }
    assertThat(selected).hasSize(1);
    tasks.claim(selected.getFirst().job());
    assertThat(dispatcher.reserveNext().job()).isNull();
    assertThat(Db.num(db.one("SELECT COUNT(*) AS n FROM jobs WHERE state='RUNNING'"), "n"))
        .isEqualTo(1);
  }

  @Test
  void expiredNotificationRetriesAndLateConfirmationCannotAcknowledgeNewAttempt() {
    tenant(0, 1);
    var old = dispatcher.reserveNext();
    db.exec(
        "UPDATE jobs SET dispatch_until=? WHERE id=?",
        Timestamp.from(Instant.now().minusSeconds(1)),
        old.job());
    var next = dispatcher.reserveNext();
    assertThat(next.job()).isEqualTo(old.job());
    assertThat(next.token()).isNotEqualTo(old.token());
    dispatcher.confirmed(old);
    assertThat(Db.bool(db.one("SELECT sent FROM outbox WHERE job_id=?", next.job()), "sent"))
        .isFalse();
    tasks.claim(next.job());
    dispatcher.confirmed(next);
    assertThat(Db.bool(db.one("SELECT sent FROM outbox WHERE job_id=?", next.job()), "sent"))
        .isTrue();
    db.exec("UPDATE jobs SET state='CANCELLED' WHERE id=?", next.job());
    assertThat(dispatcher.reserveNext()).isNull();
  }

  @Test
  void globalNotificationBoundIsFiniteAndCursorContinuesAfterCapacityReturns() {
    for (int i = 0; i < 34; i++) tenant(i, 1);
    for (int i = 0; i < 32; i++) assertThat(dispatcher.reserveNext().job()).isNotNull();
    assertThat(dispatcher.reserveNext()).isNull();
    db.exec("UPDATE jobs SET state='DONE' WHERE tenant_id='tenant-000'");
    assertThat(dispatcher.reserveNext().tenant()).isEqualTo("tenant-032");
  }
}
