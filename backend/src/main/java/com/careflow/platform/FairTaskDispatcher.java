package com.careflow.platform;

import static com.careflow.platform.Db.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable round-robin admission; broker I/O never holds database locks. */
@Service
public class FairTaskDispatcher {
  private static final Logger log = LoggerFactory.getLogger(FairTaskDispatcher.class);
  private final Db db;
  private final TransactionTemplate tx;
  private final EntitlementService entitlements;
  private final Tasks tasks;
  private final RabbitTemplate rabbit;
  private final boolean enabled;

  public FairTaskDispatcher(
      Db db,
      TransactionTemplate tx,
      EntitlementService entitlements,
      Tasks tasks,
      RabbitTemplate rabbit,
      @Value("${careflow.scheduling}") boolean enabled) {
    this.db = db;
    this.tx = tx;
    this.entitlements = entitlements;
    this.tasks = tasks;
    this.rabbit = rabbit;
    this.enabled = enabled;
  }

  record Selection(String tenant, String job, String token) {}

  // Package-visible for behavior tests. A selection with no job means this tenant is waiting.
  Selection reserveNext() {
    return tx.execute(
        status -> {
          var cursor = db.one("SELECT tenant_id FROM task_dispatch_cursor WHERE id=1 FOR UPDATE");
          if (num(
                  db.one(
                      "SELECT COUNT(*) AS n FROM jobs WHERE state='QUEUED' AND dispatch_until>CURRENT_TIMESTAMP"),
                  "n")
              >= 32) return null;
          String candidates =
              "SELECT DISTINCT tenant_id FROM jobs WHERE state='QUEUED' AND (dispatch_until IS NULL OR dispatch_until<=CURRENT_TIMESTAMP) AND EXISTS (SELECT 1 FROM outbox WHERE job_id=jobs.id)";
          var tenants =
              db.list(
                  candidates + " AND tenant_id>? ORDER BY tenant_id LIMIT 1",
                  str(cursor, "tenant_id"));
          if (tenants.isEmpty()) tenants = db.list(candidates + " ORDER BY tenant_id LIMIT 1");
          if (tenants.isEmpty()) return null;
          String tenant = str(tenants.getFirst(), "tenant_id");
          db.exec("UPDATE task_dispatch_cursor SET tenant_id=? WHERE id=1", tenant);
          db.one("SELECT id FROM tenants WHERE id=? FOR UPDATE", tenant);
          var jobs =
              db.list(
                  "SELECT * FROM jobs WHERE tenant_id=? AND state='QUEUED' AND (dispatch_until IS NULL OR dispatch_until<=CURRENT_TIMESTAMP) AND EXISTS (SELECT 1 FROM outbox WHERE job_id=jobs.id) ORDER BY quota_counted DESC,created_at,id LIMIT 1 FOR UPDATE",
                  tenant);
          if (jobs.isEmpty()) return new Selection(tenant, null, null);
          var job = jobs.getFirst();
          String id = str(job, "id");
          if (db.list(
                  "SELECT d.id FROM document_versions v JOIN documents d ON d.id=v.document_id JOIN knowledge_bases k ON k.id=d.kb_id WHERE v.id=? AND d.status='ACTIVE' AND k.status='ACTIVE'",
                  str(job, "version_id"))
              .isEmpty()) {
            db.exec(
                "UPDATE jobs SET state='CANCELLED',wait_reason=NULL,dispatch_until=NULL,dispatch_token=NULL WHERE id=?",
                id);
            return new Selection(tenant, null, null);
          }
          String wait = null;
          if (num(
                  db.one(
                      "SELECT COUNT(*) AS n FROM jobs WHERE tenant_id=? AND state='QUEUED' AND dispatch_until>CURRENT_TIMESTAMP",
                      tenant),
                  "n")
              > 0) wait = "TENANT_NOTIFICATION_PENDING";
          else
            try {
              entitlements.checkTask(tenant, job);
            } catch (ApiException failure) {
              if (failure.status != 403 && failure.status != 429) throw failure;
              wait =
                  failure.code.equals("ENTITLEMENT_INACTIVE")
                      ? "ENTITLEMENT_INACTIVE"
                      : "TASK_QUOTA_OR_CONCURRENCY";
            }
          if (wait != null) {
            db.exec("UPDATE jobs SET wait_reason=? WHERE id=?", wait, id);
            return new Selection(tenant, null, null);
          }
          String token = id();
          db.exec(
              "UPDATE jobs SET dispatch_until=?,dispatch_token=?,wait_reason='WAITING_FOR_WORKER' WHERE id=?",
              Timestamp.from(Instant.now().plusSeconds(120)),
              token,
              id);
          return new Selection(tenant, id, token);
        });
  }

  void confirmed(Selection selected) {
    tx.executeWithoutResult(
        status -> {
          db.one("SELECT id FROM tenants WHERE id=? FOR UPDATE", selected.tenant());
          var current = db.one("SELECT * FROM jobs WHERE id=? FOR UPDATE", selected.job());
          // A late publisher confirmation must never acknowledge a later retry notification.
          if (selected.token().equals(str(current, "dispatch_token")))
            db.exec("UPDATE outbox SET sent=TRUE WHERE job_id=?", selected.job());
        });
  }

  @Scheduled(fixedDelay = 5000)
  public void dispatch() {
    if (!enabled) return;
    try {
      tasks.recover();
      Set<String> waitingTenants = new HashSet<>();
      for (int scanned = 0, sent = 0; scanned < 100 && sent < 20; scanned++) {
        Selection selected = reserveNext();
        if (selected == null) break;
        if (selected.job() == null) {
          if (!waitingTenants.add(selected.tenant())) break;
          continue;
        }
        CorrelationData correlation = new CorrelationData(selected.token());
        rabbit.convertAndSend("", "careflow.processing", selected.job(), correlation);
        var confirmation = correlation.getFuture().get(5, TimeUnit.SECONDS);
        if (confirmation.isAck() && correlation.getReturned() == null) confirmed(selected);
        else log.warn("Task notification unconfirmed; durable reservation will retry");
        sent++;
      }
    } catch (Exception failure) {
      log.warn("Task dispatcher unavailable; durable reservations will retry");
    }
  }
}
