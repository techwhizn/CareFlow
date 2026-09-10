package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CleanupReadService {
  private final Db db;
  private final Identity auth;

  public CleanupReadService(Db db, Identity auth) {
    this.db = db;
    this.auth = auth;
  }

  private boolean administrator(Actor actor) {
    return !actor.app() && Set.of("OWNER", "ADMIN").contains(actor.role());
  }

  public Object list(Actor actor) {
    if (actor.app()) throw ApiException.hidden();
    var args = new ArrayList<Object>(List.of(actor.tenant()));
    if (!administrator(actor)) args.add(actor.subject());
    return db.list(
        "SELECT id,resource_type,resource_id,state,phase,attempts,failures,error_code,created_at,not_before,deadline_at,completed_at,(deadline_at<CURRENT_TIMESTAMP AND state<>'DONE') AS overdue FROM cleanup_requests WHERE tenant_id=?"
            + (administrator(actor) ? "" : " AND requested_by=?")
            + " ORDER BY created_at DESC,id DESC LIMIT 100",
        args.toArray());
  }

  public record Retry(@NotBlank @Size(max = 1000) String reason) {}

  @Transactional
  public void retry(Actor actor, String id, Retry input) {
    if (actor.app()) throw ApiException.hidden();
    auth.lock(actor);
    var row =
        db.one(
            "SELECT * FROM cleanup_requests WHERE tenant_id=? AND id=? FOR UPDATE",
            actor.tenant(),
            id);
    if (!administrator(actor) && !str(row, "requested_by").equals(actor.subject()))
      throw ApiException.hidden();
    if (!Set.of("RETRY", "BLOCKED").contains(str(row, "state"))) throw ApiException.conflict();
    db.exec(
        "UPDATE cleanup_requests SET state='PENDING',not_before=CURRENT_TIMESTAMP,lease_token=NULL,lease_until=NULL WHERE id=?",
        id);
    auth.audit(actor, "PHYSICAL_CLEANUP_RETRY_REQUEST", id, input.reason());
  }
}
