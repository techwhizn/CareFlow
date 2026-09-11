package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IntegrationService {
  public record Create(
      @NotBlank @Size(max = 200) String name,
      @NotBlank @Pattern(regexp = "webhook") String kind,
      @NotBlank @Size(max = 2000) String endpoint_url,
      @NotBlank @Size(min = 16, max = 256) String secret,
      @Size(max = 20) Set<@Pattern(regexp = "[A-Z][A-Z0-9_.-]{1,63}") String> events) {}

  public record View(
      String id,
      String name,
      String kind,
      String endpoint_url,
      Set<String> events,
      boolean active,
      long revision) {}

  private final Db db;
  private final Identity auth;
  private final ModelKeyVault vault;

  public IntegrationService(Db db, Identity auth, ModelKeyVault vault) {
    this.db = db;
    this.auth = auth;
    this.vault = vault;
  }

  @Transactional
  public View create(Actor actor, Create input) {
    auth.manager(actor);
    URI uri;
    try {
      uri = URI.create(input.endpoint_url());
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, "INVALID_ENDPOINT", "集成地址无效");
    }
    if (!"https".equalsIgnoreCase(uri.getScheme())
        && !("http".equalsIgnoreCase(uri.getScheme())
            && Set.of("localhost", "127.0.0.1").contains(uri.getHost())))
      throw new ApiException(400, "INVALID_ENDPOINT", "集成地址必须使用 HTTPS");
    String id = Db.id();
    String events =
        String.join(",", new TreeSet<>(input.events() == null ? Set.of() : input.events()));
    db.exec(
        "INSERT INTO integration_endpoints(id,tenant_id,name,kind,endpoint_url,secret_ciphertext,events) VALUES(?,?,?,?,?,?,?)",
        id,
        actor.tenant(),
        input.name(),
        input.kind(),
        input.endpoint_url(),
        vault.encrypt(actor.tenant(), id, input.secret()),
        events);
    auth.audit(actor, "INTEGRATION_CREATE", id, "kind=" + input.kind());
    return view(
        db.one(
            "SELECT * FROM integration_endpoints WHERE tenant_id=? AND id=?", actor.tenant(), id));
  }

  public List<View> list(Actor actor) {
    auth.manager(actor);
    return db
        .list(
            "SELECT * FROM integration_endpoints WHERE tenant_id=? ORDER BY created_at DESC",
            actor.tenant())
        .stream()
        .map(this::view)
        .toList();
  }

  @Transactional
  public void disable(Actor actor, String id, long revision) {
    auth.manager(actor);
    if (db.exec(
            "UPDATE integration_endpoints SET active=FALSE,revision=revision+1 WHERE tenant_id=? AND id=? AND revision=?",
            actor.tenant(),
            id,
            revision)
        != 1) throw new ApiException(409, "REVISION_CONFLICT", "集成配置已变化，请刷新后重试");
    auth.audit(actor, "INTEGRATION_DISABLE", id, "");
  }

  private View view(Map<String, Object> row) {
    String raw = Db.str(row, "events");
    Set<String> events = raw.isBlank() ? Set.of() : Set.copyOf(Arrays.asList(raw.split(",")));
    return new View(
        Db.str(row, "id"),
        Db.str(row, "name"),
        Db.str(row, "kind"),
        Db.str(row, "endpoint_url"),
        events,
        Db.bool(row, "active"),
        Db.num(row, "revision"));
  }
}
