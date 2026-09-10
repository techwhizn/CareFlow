package com.careflow.platform;

import static com.careflow.platform.Db.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class UploadStaging {
  private final Db db;
  private final BlobStore blobs;
  private final TransactionTemplate tx;
  private final boolean enabled;

  public UploadStaging(
      Db db,
      BlobStore blobs,
      TransactionTemplate tx,
      @Value("${careflow.scheduling}") boolean enabled) {
    this.db = db;
    this.blobs = blobs;
    this.tx = tx;
    this.enabled = enabled;
  }

  // Called inside the short prepare transaction; every object key is unique and never reused.
  public void reserve(String id, String tenant, String key) {
    db.exec(
        "INSERT INTO upload_staging(id,tenant_id,object_key,expires_at) VALUES(?,?,?,?)",
        id,
        tenant,
        key,
        Timestamp.from(Instant.now().plusSeconds(900)));
  }

  public void attach(String id, String tenant) {
    if (db.exec(
            "UPDATE upload_staging SET state='ATTACHED' WHERE id=? AND tenant_id=? AND state='PENDING' AND expires_at>CURRENT_TIMESTAMP",
            id,
            tenant)
        != 1) throw new ApiException(409, "UPLOAD_EXPIRED", "上传暂存已过期，请使用新请求重试");
  }

  public void abandon(String id) {
    // If this update is unavailable, the original expiry still makes the object reclaimable.
    try {
      db.exec(
          "UPDATE upload_staging SET expires_at=CURRENT_TIMESTAMP WHERE id=? AND state='PENDING'",
          id);
    } catch (org.springframework.dao.DataAccessException ignored) {
    }
  }

  @Scheduled(fixedDelay = 60000)
  public void scheduledCleanup() {
    if (enabled) cleanup();
  }

  public void cleanup() {
    for (var row :
        db.list(
            "SELECT id FROM upload_staging WHERE state IN ('PENDING','CLEANING') AND expires_at<CURRENT_TIMESTAMP ORDER BY expires_at LIMIT 20")) {
      try {
        var staged =
            tx.execute(
                status -> {
                  String id = str(row, "id");
                  if (db.exec(
                          "UPDATE upload_staging SET state='CLEANING',attempts=attempts+1,expires_at=? WHERE id=? AND state IN ('PENDING','CLEANING') AND expires_at<CURRENT_TIMESTAMP",
                          Timestamp.from(Instant.now().plusSeconds(300)),
                          id)
                      != 1) return null;
                  var item = db.one("SELECT * FROM upload_staging WHERE id=?", id);
                  if (!db.list(
                          "SELECT id FROM document_versions WHERE object_key=?",
                          str(item, "object_key"))
                      .isEmpty()) {
                    db.exec("UPDATE upload_staging SET state='ATTACHED' WHERE id=?", id);
                    return null;
                  }
                  return item;
                });
        if (staged == null) continue;
        blobs.delete(str(staged, "object_key"));
        tx.executeWithoutResult(
            status -> {
              if (db.exec(
                      "UPDATE upload_staging SET state='DELETED' WHERE id=? AND state='CLEANING'",
                      str(staged, "id"))
                  == 1)
                db.exec(
                    "INSERT INTO audit_events(id,tenant_id,actor_id,action,resource_id,details) VALUES(?,?,'SYSTEM','UPLOAD_STAGING_CLEANUP',?,'')",
                    id(),
                    str(staged, "tenant_id"),
                    str(staged, "id"));
            });
      } catch (RuntimeException ignored) {
        // A five-minute cleanup lease permits retry after storage or process failure.
      }
    }
  }
}
