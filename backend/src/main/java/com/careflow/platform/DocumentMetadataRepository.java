package com.careflow.platform;

import java.time.ZoneOffset;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentMetadataRepository {
  private final Db db;

  public DocumentMetadataRepository(Db db) {
    this.db = db;
  }

  public int update(
      String tenant,
      String id,
      DocumentMetadataService.Metadata input,
      String tags,
      String models) {
    return db.exec(
        "UPDATE documents SET title=?,source=?,language=?,tags_json=?,product_models_json=?,valid_from=?,valid_until=?,revision=revision+1 WHERE tenant_id=? AND id=? AND revision=?",
        input.title().trim(),
        input.source(),
        input.language(),
        tags,
        models,
        input.valid_from() == null
            ? null
            : input.valid_from().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(),
        input.valid_until() == null
            ? null
            : input.valid_until().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(),
        tenant,
        id,
        input.revision());
  }

  public void history(String tenant, String id, long revision, String actor, String snapshot) {
    db.exec(
        "INSERT INTO document_metadata_history(id,tenant_id,document_id,revision,actor_id,metadata_json) VALUES(?,?,?,?,?,?)",
        Db.id(),
        tenant,
        id,
        revision,
        actor,
        snapshot);
  }

  public List<Map<String, Object>> history(String tenant, String id, int page) {
    return db.list(
        "SELECT revision,actor_id,metadata_json,created_at FROM document_metadata_history WHERE tenant_id=? AND document_id=? ORDER BY revision DESC LIMIT 50 OFFSET ?",
        tenant,
        id,
        page * 50);
  }
}
