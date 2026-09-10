package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MembershipService {
  public record Create(
      @NotBlank @Size(max = 200) String name,
      @NotNull @Pattern(regexp = "ADMIN|KNOWLEDGE_MANAGER|DEVELOPER|USER") String role) {}

  public record Change(
      @NotBlank @Size(max = 200) String name,
      @NotNull @Pattern(regexp = "OWNER|ADMIN|KNOWLEDGE_MANAGER|DEVELOPER|USER") String role,
      @NotNull @Pattern(regexp = "ACTIVE|DISABLED|REMOVED") String state,
      @Min(0) long revision) {}

  private final Db db;
  private final Identity auth;

  public MembershipService(Db db, Identity auth) {
    this.db = db;
    this.auth = auth;
  }

  public List<Map<String, Object>> list(Actor actor) {
    auth.admin(actor);
    return db.list("SELECT * FROM members WHERE tenant_id=? ORDER BY name,id", actor.tenant());
  }

  @Transactional
  public Map<String, Object> create(Actor actor, Create input) {
    auth.admin(actor);
    auth.lock(actor);
    String member = id();
    db.exec(
        "INSERT INTO members(id,tenant_id,name,role) VALUES(?,?,?,?)",
        member,
        actor.tenant(),
        input.name(),
        input.role());
    auth.audit(actor, "MEMBER_CREATE", member, input.role());
    return Map.of("id", member, "token", auth.credential(actor.tenant(), member, "MEMBER", null));
  }

  @Transactional
  public Map<String, Object> change(Actor actor, String member, Change input) {
    auth.admin(actor);
    auth.lock(actor);
    var before = db.one("SELECT * FROM members WHERE tenant_id=? AND id=?", actor.tenant(), member);
    if (bool(before, "removed")) throw new ApiException(409, "MEMBER_REMOVED", "已移除成员不能恢复，请重新邀请");
    if ((str(before, "role").equals("OWNER")
            && (!input.role().equals("OWNER") || !input.state().equals("ACTIVE")))
        || (!str(before, "role").equals("OWNER") && input.role().equals("OWNER")))
      throw new ApiException(409, "OWNER_REQUIRED", "此操作不能修改企业所有权或禁用所有者");
    boolean active = input.state().equals("ACTIVE"), removed = input.state().equals("REMOVED");
    if (removed
        && !db.list(
                "SELECT id FROM knowledge_bases WHERE tenant_id=? AND owner_id=? AND status<>'DELETED'",
                actor.tenant(),
                member)
            .isEmpty()) throw new ApiException(409, "RESOURCE_OWNER_REQUIRED", "请先移交该成员负责的知识库");
    if (db.exec(
            "UPDATE members SET name=?,role=?,active=?,removed=?,revision=revision+1 WHERE tenant_id=? AND id=? AND revision=?",
            input.name(),
            input.role(),
            active,
            removed,
            actor.tenant(),
            member,
            input.revision())
        != 1) throw ApiException.conflict();
    if (!active)
      db.exec(
          "UPDATE credentials SET active=FALSE WHERE tenant_id=? AND subject_id=? AND kind='MEMBER'",
          actor.tenant(),
          member);
    if (removed)
      db.exec("DELETE FROM permissions WHERE tenant_id=? AND subject_id=?", actor.tenant(), member);
    auth.audit(actor, "MEMBER_UPDATE", member, "role=" + input.role() + ",state=" + input.state());
    return db.one("SELECT * FROM members WHERE tenant_id=? AND id=?", actor.tenant(), member);
  }

  @Transactional
  public void disable(Actor actor, String member) {
    auth.admin(actor);
    auth.lock(actor);
    var row = db.one("SELECT * FROM members WHERE tenant_id=? AND id=?", actor.tenant(), member);
    change(
        actor,
        member,
        new Change(str(row, "name"), str(row, "role"), "DISABLED", num(row, "revision")));
  }

  @Transactional
  public Map<String, String> issueCredential(Actor actor, String member) {
    auth.admin(actor);
    auth.lock(actor);
    var target =
        db.one(
            "SELECT id,role FROM members WHERE tenant_id=? AND id=? AND active=TRUE AND removed=FALSE",
            actor.tenant(),
            member);
    if (str(target, "role").equals("OWNER") && !actor.subject().equals(member))
      throw ApiException.hidden();
    auth.audit(actor, "MEMBER_CREDENTIAL_CREATE", member, "");
    return Map.of("token", auth.credential(actor.tenant(), member, "MEMBER", null));
  }
}
