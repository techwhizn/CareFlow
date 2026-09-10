package com.careflow.platform;

import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class KnowledgeConfigurationRepository {
  private final Db db;

  public KnowledgeConfigurationRepository(Db db) {
    this.db = db;
  }

  public Map<String, Object> get(String tenant, String id) {
    return db.one("SELECT * FROM knowledge_configurations WHERE tenant_id=? AND id=?", tenant, id);
  }

  public List<Map<String, Object>> list(String tenant, String kb) {
    return db.list(
        "SELECT * FROM knowledge_configurations WHERE tenant_id=? AND kb_id=? ORDER BY created_at DESC,id DESC LIMIT 100",
        tenant,
        kb);
  }

  public void create(
      String tenant, String kb, String id, String definition, String models, String actor) {
    db.exec(
        "INSERT INTO knowledge_configurations(id,tenant_id,kb_id,definition_json,models_json,actor_id) VALUES(?,?,?,?,?,?)",
        id,
        tenant,
        kb,
        definition,
        models,
        actor);
  }

  public void publish(
      String tenant, String kb, String id, String previous, long revision, String actor) {
    if (db.exec(
            "UPDATE knowledge_bases SET published_configuration=?,configuration_revision=configuration_revision+1 WHERE tenant_id=? AND id=? AND configuration_revision=?",
            id,
            tenant,
            kb,
            revision)
        != 1) throw ApiException.conflict();
    db.exec(
        "UPDATE knowledge_configurations SET ever_published=TRUE WHERE tenant_id=? AND id=?",
        tenant,
        id);
    db.exec(
        "INSERT INTO knowledge_configuration_publications(id,tenant_id,kb_id,configuration_id,previous_configuration,revision,actor_id) VALUES(?,?,?,?,?,?,?)",
        Db.id(),
        tenant,
        kb,
        id,
        previous,
        revision + 1,
        actor);
  }
}
