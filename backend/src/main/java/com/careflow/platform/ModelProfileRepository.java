package com.careflow.platform;

import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class ModelProfileRepository {
  private final Db db;

  public ModelProfileRepository(Db db) {
    this.db = db;
  }

  public List<Map<String, Object>> list(String tenant) {
    return db.list("SELECT * FROM model_profiles WHERE tenant_id=? ORDER BY created_at,id", tenant);
  }

  public Map<String, Object> get(String tenant, String id) {
    return db.one("SELECT * FROM model_profiles WHERE tenant_id=? AND id=?", tenant, id);
  }

  public void create(String tenant, String id, ModelProfileService.Input i, String encrypted) {
    db.exec(
        "INSERT INTO model_profiles(id,tenant_id,name,kind,base_url,model,model_revision,dimensions,external_processing,encrypted_key) VALUES(?,?,?,?,?,?,?,?,?,?)",
        id,
        tenant,
        i.name(),
        i.kind(),
        i.base_url(),
        i.model(),
        i.model_revision(),
        i.dimensions(),
        i.external_processing(),
        encrypted);
  }

  public void update(String tenant, String id, ModelProfileService.Input i, String encrypted) {
    if (db.exec(
            "UPDATE model_profiles SET name=?,kind=?,base_url=?,model=?,model_revision=?,dimensions=?,external_processing=?,encrypted_key=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=? AND revision=?",
            i.name(),
            i.kind(),
            i.base_url(),
            i.model(),
            i.model_revision(),
            i.dimensions(),
            i.external_processing(),
            encrypted,
            tenant,
            id,
            i.revision())
        != 1) throw ApiException.conflict();
  }
}
