package com.careflow.platform;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class Identity {
  public record Actor(String tenant, String subject, String kind, String role, Set<String> scopes) {
    public Actor(String tenant, String subject, String kind, String role) {
      this(tenant, subject, kind, role, Set.of("READ", "SEARCH", "ANSWER"));
    }

    public Actor {
      scopes = Set.copyOf(scopes);
    }

    public boolean app() {
      return kind.equals("APP");
    }
  }

  private final Db db;

  public Identity(Db db) {
    this.db = db;
  }

  public static String hash(String raw) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public Actor authenticate(String authorization) {
    if (authorization == null || !authorization.startsWith("Bearer "))
      throw new ApiException(401, "UNAUTHENTICATED", "请提供有效凭证");
    var rows =
        db.list(
            "SELECT * FROM credentials WHERE digest=? AND active=TRUE AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP)",
            hash(authorization.substring(7)));
    if (rows.isEmpty()) throw new ApiException(401, "UNAUTHENTICATED", "凭证无效或已失效");
    var c = rows.getFirst();
    String t = Db.str(c, "tenant_id"), s = Db.str(c, "subject_id"), kind = Db.str(c, "kind");
    if (kind.equals("APP")) {
      db.one("SELECT id FROM applications WHERE tenant_id=? AND id=? AND published=TRUE", t, s);
      return new Actor(
          t, s, kind, "APPLICATION", new HashSet<>(Arrays.asList(Db.str(c, "scopes").split(","))));
    }
    var m =
        db.one(
            "SELECT * FROM members WHERE tenant_id=? AND id=? AND active=TRUE AND removed=FALSE",
            t,
            s);
    return new Actor(t, s, kind, Db.str(m, "role"));
  }

  public void authorizeRequest(Actor actor, String method, String path) {
    if (!actor.app()) return;
    String required =
        method.equals("GET")
            ? "READ"
            : method.equals("POST") && path.equals("/api/v1/retrieval/search")
                ? "SEARCH"
                : method.equals("POST") && path.equals("/api/v1/answers") ? "ANSWER" : null;
    if (required == null || !actor.scopes().contains(required)) throw ApiException.hidden();
  }

  public void manager(Actor a) {
    if (a.app() || !Set.of("OWNER", "ADMIN", "KNOWLEDGE_MANAGER").contains(a.role()))
      throw ApiException.hidden();
  }

  public void admin(Actor a) {
    if (a.app() || !Set.of("OWNER", "ADMIN").contains(a.role())) throw ApiException.hidden();
  }

  public void developer(Actor a) {
    if (a.app() || !Set.of("OWNER", "ADMIN", "DEVELOPER").contains(a.role()))
      throw ApiException.hidden();
  }

  public boolean granted(Actor a, String resource, String action) {
    return !db.list(
            "SELECT action FROM permissions WHERE tenant_id=? AND resource_id=? AND subject_id=? AND action=?",
            a.tenant(),
            resource,
            a.subject(),
            action)
        .isEmpty();
  }

  public Map<String, Object> kb(Actor a, String id, String action) {
    var k =
        db.one(
            "SELECT * FROM knowledge_bases WHERE tenant_id=? AND id=? AND status<>'DELETED'",
            a.tenant(),
            id);
    if (Set.of("edit", "publish", "manage").contains(action)) manager(a);
    boolean owner = Db.str(k, "owner_id").equals(a.subject()) && !a.app();
    if (!owner && !granted(a, id, action)) throw ApiException.hidden();
    return k;
  }

  public Map<String, Object> document(Actor a, String id, String action) {
    var d =
        db.one(
            "SELECT * FROM documents WHERE tenant_id=? AND id=? AND status<>'DELETED'",
            a.tenant(),
            id);
    kb(a, Db.str(d, "kb_id"), action);
    if (Db.bool(d, "restricted") && !granted(a, id, action)) throw ApiException.hidden();
    return d;
  }

  public Map<String, Object> version(Actor a, String id, String action) {
    var v = db.one("SELECT * FROM document_versions WHERE tenant_id=? AND id=?", a.tenant(), id);
    var d = document(a, Db.str(v, "document_id"), action);
    boolean inspectDraft = false;
    if (!a.app() && Set.of("OWNER", "ADMIN", "KNOWLEDGE_MANAGER").contains(a.role())) {
      var knowledgeBase = kb(a, Db.str(d, "kb_id"), "read");
      inspectDraft =
          Db.str(knowledgeBase, "owner_id").equals(a.subject())
              || granted(a, Db.str(d, "kb_id"), "edit");
    }
    if (Set.of("read", "download").contains(action)
        && !Db.bool(v, "ever_published")
        && !inspectDraft) throw ApiException.hidden();
    return v;
  }

  public void lock(Actor a) {
    db.one("SELECT id FROM tenants WHERE id=? FOR UPDATE", a.tenant());
  }

  public void audit(Actor a, String action, String id, String details) {
    db.exec(
        "INSERT INTO audit_events(id,tenant_id,actor_id,action,resource_id,details) VALUES(?,?,?,?,?,?)",
        Db.id(),
        a.tenant(),
        a.subject(),
        action,
        id,
        details);
  }

  public String credential(String tenant, String subject, String kind, java.sql.Timestamp expires) {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    String raw = "cf_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    db.exec(
        "INSERT INTO credentials(id,tenant_id,subject_id,kind,digest,expires_at) VALUES(?,?,?,?,?,?)",
        Db.id(),
        tenant,
        subject,
        kind,
        hash(raw),
        expires);
    return raw;
  }
}
