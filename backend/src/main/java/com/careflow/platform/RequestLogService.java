package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import java.util.*;
import org.springframework.stereotype.Service;

/** Body-free operational records; no idempotency keys, credentials or evidence text. */
@Service
public class RequestLogService {
  private final Db db;
  private final Identity auth;
  private final ApplicationUsageAccess applications;

  public RequestLogService(Db db, Identity auth, ApplicationUsageAccess applications) {
    this.db = db;
    this.auth = auth;
    this.applications = applications;
  }

  private void access(Actor actor, String app) {
    if (app == null) auth.admin(actor);
    else applications.require(actor, app);
  }

  private String filter(String app) {
    return app == null ? "" : " AND application_id=?";
  }

  private ArrayList<Object> args(Actor actor, String app) {
    var args = new ArrayList<Object>();
    args.add(actor.tenant());
    if (app != null) args.add(app);
    return args;
  }

  private static final String COLUMNS =
      "id AS request_id,application_id,operation,state,outcome,error_code,created_at,completed_at,application_revision,configuration_id,application_configuration_id";

  public Object list(Actor actor, String app, UUID before) {
    access(actor, app);
    var values = args(actor, app);
    String cursor = "";
    if (before != null) {
      var lookup = args(actor, app);
      lookup.add(before.toString());
      var previous =
          db.one(
              "SELECT created_at,id FROM usage_events WHERE tenant_id=? AND resource_type='QUERY'"
                  + filter(app)
                  + " AND id=?",
              lookup.toArray());
      cursor = " AND (created_at < ? OR (created_at = ? AND id < ?))";
      values.add(previous.get("created_at"));
      values.add(previous.get("created_at"));
      values.add(previous.get("id"));
    }
    var rows =
        db.list(
            "SELECT "
                + COLUMNS
                + " FROM usage_events WHERE tenant_id=? AND resource_type='QUERY'"
                + filter(app)
                + cursor
                + " ORDER BY created_at DESC,id DESC LIMIT 101",
            values.toArray());
    boolean more = rows.size() > 100;
    var page = more ? rows.subList(0, 100) : rows;
    return Map.of("items", page, "next_cursor", more ? str(page.getLast(), "request_id") : "");
  }

  public Object detail(Actor actor, String app, UUID request) {
    access(actor, app);
    var values = args(actor, app);
    values.add(request.toString());
    var row =
        db.one(
            "SELECT "
                + COLUMNS
                + " FROM usage_events WHERE tenant_id=? AND resource_type='QUERY'"
                + filter(app)
                + " AND id=?",
            values.toArray());
    row.put(
        "retrieval_calls",
        db.list(
            "SELECT id,stage,configuration_id,input_count,call_state,usage_state,total_tokens,created_at,completed_at FROM retrieval_model_calls WHERE tenant_id=? AND request_id=? ORDER BY created_at,id",
            actor.tenant(),
            request.toString()));
    row.put(
        "generation",
        db.list(
            "SELECT configuration_id,request_state,usage_state,input_tokens,output_tokens,total_tokens,created_at,completed_at FROM generation_usage WHERE tenant_id=? AND request_id=?",
            actor.tenant(),
            request.toString()));
    return row;
  }
}
