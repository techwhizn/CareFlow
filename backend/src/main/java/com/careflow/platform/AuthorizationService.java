package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthorizationService {
  public enum Resource {
    KNOWLEDGE_BASE,
    DOCUMENT
  }

  public record PermissionChange(
      @NotNull @Size(max = 500) Map<String, List<String>> grants, @Min(0) long revision) {}

  private final Db db;
  private final Identity auth;

  public AuthorizationService(Db db, Identity auth) {
    this.db = db;
    this.auth = auth;
  }

  private Map<String, Object> require(Actor actor, String id, Resource type) {
    return type == Resource.DOCUMENT
        ? auth.document(actor, id, "manage")
        : auth.kb(actor, id, "manage");
  }

  public List<Map<String, Object>> grants(Actor actor, String id, Resource type) {
    require(actor, id, type);
    return db.list(
        "SELECT subject_id,action FROM permissions WHERE tenant_id=? AND resource_id=? ORDER BY subject_id,action",
        actor.tenant(),
        id);
  }

  @Transactional
  public Map<String, Object> snapshot(Actor actor, String id, Resource type) {
    auth.lock(actor);
    var resource = require(actor, id, type);
    return Map.of(
        "revision",
        num(resource, "revision"),
        "grants",
        grants(actor, id, type),
        "subjects",
        db.list(
            "SELECT id,name,'MEMBER' AS kind,active FROM members WHERE tenant_id=? AND removed=FALSE UNION ALL SELECT id,name,'APP' AS kind,TRUE AS active FROM applications WHERE tenant_id=?",
            actor.tenant(),
            actor.tenant()),
        "restricted",
        type == Resource.DOCUMENT && bool(resource, "restricted"));
  }

  @Transactional
  public void replace(Actor actor, String id, Resource type, PermissionChange input) {
    auth.lock(actor);
    var resource = require(actor, id, type);
    Map<String, Set<String>> normalized = new LinkedHashMap<>();
    for (var grant : input.grants().entrySet()) {
      if (grant.getKey() == null || grant.getValue() == null || grant.getValue().size() > 5)
        throw new IllegalArgumentException();
      var matches =
          db.list(
              "SELECT id FROM members WHERE tenant_id=? AND id=? AND removed=FALSE UNION ALL SELECT id FROM applications WHERE tenant_id=? AND id=?",
              actor.tenant(),
              grant.getKey(),
              actor.tenant(),
              grant.getKey());
      if (matches.isEmpty()) throw ApiException.hidden();
      Set<String> actions = new HashSet<>(grant.getValue());
      if (actions.contains(null)
          || !Set.of("read", "download", "edit", "publish", "manage").containsAll(actions))
        throw new IllegalArgumentException();
      if (type == Resource.DOCUMENT) {
        var kb = auth.kb(actor, str(resource, "kb_id"), "manage");
        for (String action : actions) {
          boolean owner = str(kb, "owner_id").equals(grant.getKey());
          if (!owner
              && db.list(
                      "SELECT action FROM permissions WHERE tenant_id=? AND resource_id=? AND subject_id=? AND action=?",
                      actor.tenant(),
                      str(resource, "kb_id"),
                      grant.getKey(),
                      action)
                  .isEmpty())
            throw new ApiException(400, "DOCUMENT_PERMISSION_EXCEEDS_KB", "文档授权不能超出该主体的知识库权限");
        }
      }
      normalized.put(grant.getKey(), actions);
    }
    String sql =
        type == Resource.DOCUMENT
            ? "UPDATE documents SET restricted=TRUE,revision=revision+1 WHERE tenant_id=? AND id=? AND revision=?"
            : "UPDATE knowledge_bases SET revision=revision+1 WHERE tenant_id=? AND id=? AND revision=?";
    if (db.exec(sql, actor.tenant(), id, input.revision()) != 1) throw ApiException.conflict();
    db.exec("DELETE FROM permissions WHERE tenant_id=? AND resource_id=?", actor.tenant(), id);
    for (var grant : normalized.entrySet())
      for (String action : grant.getValue())
        db.exec(
            "INSERT INTO permissions(tenant_id,resource_id,subject_id,action) VALUES(?,?,?,?)",
            actor.tenant(),
            id,
            grant.getKey(),
            action);
    db.exec("UPDATE tenants SET revision=revision+1 WHERE id=?", actor.tenant());
    auth.audit(actor, "ACL_REPLACE", id, "subjects=" + normalized.size());
  }
}
