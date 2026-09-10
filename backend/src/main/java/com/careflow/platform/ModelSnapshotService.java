package com.careflow.platform;

import static com.careflow.platform.Db.*;

import org.springframework.stereotype.Service;

/** Immutable model credentials shared by knowledge and application configuration versions. */
@Service
public class ModelSnapshotService {
  public record StoredModel(
      String profile_id,
      long profile_revision,
      String kind,
      String base_url,
      String model,
      String model_revision,
      Integer dimensions,
      boolean external_processing,
      String encrypted_key) {
    @Override
    public String toString() {
      return "StoredModel[redacted]";
    }
  }

  private final ModelProfileRepository profiles;
  private final ModelProfileService policy;
  private final ModelKeyVault vault;

  public ModelSnapshotService(
      ModelProfileRepository profiles, ModelProfileService policy, ModelKeyVault vault) {
    this.profiles = profiles;
    this.policy = policy;
    this.vault = vault;
  }

  public StoredModel capture(String tenant, String id, String kind, long revision) {
    var row = profiles.get(tenant, id);
    if (num(row, "revision") != revision)
      throw new ApiException(409, "MODEL_PROFILE_CHANGED", "模型档案已更新，请重新查看后保存");
    if (!kind.equals(str(row, "kind")))
      throw new IllegalArgumentException("Model profile kind mismatch");
    policy.checkEndpoint(str(row, "base_url"), bool(row, "external_processing"));
    return new StoredModel(
        id,
        num(row, "revision"),
        kind,
        str(row, "base_url"),
        str(row, "model"),
        str(row, "model_revision"),
        row.get("dimensions") == null ? null : ((Number) row.get("dimensions")).intValue(),
        bool(row, "external_processing"),
        str(row, "encrypted_key"));
  }

  public WorkerProtocolV1.ModelConfiguration resolve(String tenant, StoredModel model) {
    if (model == null) throw new IllegalStateException("Missing immutable model snapshot");
    policy.checkEndpoint(model.base_url(), model.external_processing());
    return new WorkerProtocolV1.ModelConfiguration(
        model.kind(),
        model.base_url(),
        model.model(),
        model.model_revision(),
        model.dimensions(),
        vault.decrypt(tenant, model.profile_id(), model.encrypted_key()));
  }
}
