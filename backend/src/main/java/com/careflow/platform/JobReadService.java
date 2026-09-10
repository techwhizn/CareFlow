package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class JobReadService {
  private final OcrAccountingService ocr;
  private final Db db;
  private final Identity auth;
  private final IndexAccountingService accounting;

  public JobReadService(
      Db db, Identity auth, IndexAccountingService accounting, OcrAccountingService ocr) {
    this.ocr = ocr;
    this.db = db;
    this.auth = auth;
    this.accounting = accounting;
  }

  public Object jobs(Actor actor) {
    return db
        .list(
            "SELECT * FROM jobs WHERE tenant_id=? ORDER BY created_at DESC LIMIT 100",
            actor.tenant())
        .stream()
        .filter(
            j -> {
              try {
                auth.version(actor, str(j, "version_id"), "read");
                return true;
              } catch (ApiException e) {
                return false;
              }
            })
        .map(this::publicJob)
        .toList();
  }

  private Map<String, Object> publicJob(Map<String, Object> job) {
    var result = new LinkedHashMap<>(job);
    result.remove("lease_token");
    result.remove("dispatch_token");
    result.remove("request_key");
    result.remove("upload_fingerprint");
    if (str(job, "kind").equals("INDEX"))
      result.put("index_usage", accounting.summary(str(job, "tenant_id"), str(job, "id")));
    if (str(job, "kind").equals("PARSE"))
      result.put("ocr_usage", ocr.summary(str(job, "tenant_id"), str(job, "id")));
    return result;
  }

  public Object job(Actor actor, String id) {
    var j = db.one("SELECT * FROM jobs WHERE tenant_id=? AND id=?", actor.tenant(), id);
    auth.version(actor, str(j, "version_id"), "read");
    return publicJob(j);
  }
}
