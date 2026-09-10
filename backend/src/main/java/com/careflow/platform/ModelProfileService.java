package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModelProfileService {
  public record Input(
      @NotBlank @Size(max = 200) String name,
      @NotNull @Pattern(regexp = "GENERATION|EMBEDDING|RERANK") String kind,
      @NotBlank @Size(max = 500) String base_url,
      @NotBlank @Size(max = 200) String model,
      @NotNull @Size(max = 200) String model_revision,
      @Min(1) @Max(65536) Integer dimensions,
      boolean external_processing,
      @Size(max = 8192) String api_key,
      @Min(0) long revision) {
    @Override
    public String toString() {
      return "ModelProfileInput[redacted]";
    }
  }

  public record View(
      String id,
      String name,
      String kind,
      String base_url,
      String model,
      String model_revision,
      Integer dimensions,
      boolean external_processing,
      boolean key_configured,
      long revision) {}

  private final Identity auth;
  private final ModelProfileRepository repository;
  private final ModelKeyVault vault;
  private final Set<String> externalBases;
  private final Set<String> localBases;

  public ModelProfileService(
      Identity auth,
      ModelProfileRepository repository,
      ModelKeyVault vault,
      @Value("${careflow.model-external-bases:}") String external,
      @Value("${careflow.model-local-bases:}") String local) {
    this.auth = auth;
    this.repository = repository;
    this.vault = vault;
    externalBases = parse(external);
    localBases = parse(local);
  }

  private static Set<String> parse(String values) {
    Set<String> result = new HashSet<>();
    for (String value : values.split(","))
      if (!value.isBlank()) result.add(canonical(value.trim()));
    return Set.copyOf(result);
  }

  private static String canonical(String value) {
    try {
      URI uri = URI.create(value);
      if (!Set.of("https", "http").contains(Objects.toString(uri.getScheme(), ""))
          || uri.getHost() == null
          || uri.getRawUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || !Set.of("", "/", "/v1", "/v1/").contains(uri.getRawPath()))
        throw new IllegalArgumentException();
      return uri.toString().replaceAll("/$", "");
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid model endpoint");
    }
  }

  public List<View> list(Actor actor) {
    auth.admin(actor);
    return repository.list(actor.tenant()).stream().map(this::view).toList();
  }

  public Map<String, Object> policy(Actor actor) {
    auth.admin(actor);
    return Map.of(
        "external_bases",
        externalBases.stream().sorted().toList(),
        "local_bases",
        localBases.stream().sorted().toList());
  }

  public void checkEndpoint(String base, boolean external) {
    if (!(external ? externalBases : localBases).contains(canonical(base)))
      throw new ApiException(400, "MODEL_ENDPOINT_NOT_ALLOWED", "模型地址未获部署管理员授权，或地址许可已撤销");
  }

  public List<View> forKnowledgeConfiguration(Actor actor, String kb) {
    auth.kb(actor, kb, "manage");
    return repository.list(actor.tenant()).stream().map(this::view).toList();
  }

  public List<View> forApplication(Actor actor) {
    auth.developer(actor);
    return repository.list(actor.tenant()).stream().map(this::view).toList();
  }

  @Transactional
  public View save(Actor actor, String profile, Input input) {
    auth.admin(actor);
    auth.lock(actor);
    String base = canonical(input.base_url());
    checkEndpoint(base, input.external_processing());
    if (input.kind().equals("EMBEDDING")
        && (input.dimensions() == null || input.model_revision().isBlank()))
      throw new IllegalArgumentException("Embedding requires dimensions and immutable revision");
    if (!input.kind().equals("EMBEDDING") && input.dimensions() != null)
      throw new IllegalArgumentException("Dimensions apply to embedding only");
    String id = profile == null ? id() : profile;
    Map<String, Object> previous = profile == null ? null : repository.get(actor.tenant(), id);
    String encrypted;
    if (input.api_key() == null) {
      if (previous != null
          && (!str(previous, "base_url").equals(base)
              || !str(previous, "kind").equals(input.kind())))
        throw new ApiException(400, "KEY_REENTRY_REQUIRED", "更换地址或模型类型需要重新填写密钥，不能沿用旧凭证");
      encrypted = previous == null ? "" : str(previous, "encrypted_key");
    } else encrypted = vault.encrypt(actor.tenant(), id, input.api_key());
    Input normalized =
        new Input(
            input.name(),
            input.kind(),
            base,
            input.model(),
            input.model_revision(),
            input.dimensions(),
            input.external_processing(),
            null,
            input.revision());
    if (previous == null) repository.create(actor.tenant(), id, normalized, encrypted);
    else repository.update(actor.tenant(), id, normalized, encrypted);
    auth.audit(
        actor,
        previous == null ? "MODEL_PROFILE_CREATE" : "MODEL_PROFILE_UPDATE",
        id,
        "kind=" + input.kind());
    return view(repository.get(actor.tenant(), id));
  }

  private View view(Map<String, Object> row) {
    return new View(
        str(row, "id"),
        str(row, "name"),
        str(row, "kind"),
        str(row, "base_url"),
        str(row, "model"),
        str(row, "model_revision"),
        row.get("dimensions") == null ? null : ((Number) row.get("dimensions")).intValue(),
        bool(row, "external_processing"),
        !str(row, "encrypted_key").isBlank(),
        num(row, "revision"));
  }
}
