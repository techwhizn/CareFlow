package com.careflow.platform;

import static com.careflow.platform.Db.*;

import java.util.*;
import org.springframework.stereotype.Service;

/** Retains all inputs attempted by a content version, including unsuccessful model calls. */
@Service
public class IndexCacheReferences {
  private final Db db;

  public IndexCacheReferences(Db db) {
    this.db = db;
  }

  public void register(String tenant, String version, String model, String generation) {
    if (!model.matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
    Set<String> recorded = new HashSet<>();
    for (var row :
        db.list(
            "SELECT content_hash FROM index_cache_references WHERE tenant_id=? AND version_id=? AND model_identity=?",
            tenant,
            version,
            model)) recorded.add(str(row, "content_hash"));
    var pending = new ArrayList<String>();
    var current = new HashSet<String>();
    for (var row :
        db.list(
            "SELECT content FROM chunks WHERE tenant_id=? AND version_id=? AND enabled=TRUE",
            tenant,
            version)) {
      String hash = IndexManifest.hash(str(row, "content"));
      current.add(hash);
      if (recorded.add(hash)) pending.add(hash);
    }
    var all = new ArrayList<>(current);
    for (int offset = 0; offset < all.size(); offset += 100) {
      var batch = all.subList(offset, Math.min(offset + 100, all.size()));
      var args = new ArrayList<Object>(List.of(generation, tenant, version, model));
      args.addAll(batch);
      db.exec(
          "UPDATE index_cache_references SET generation_id=? WHERE tenant_id=? AND version_id=? AND model_identity=? AND content_hash IN ("
              + String.join(",", Collections.nCopies(batch.size(), "?"))
              + ")",
          args.toArray());
    }
    for (int offset = 0; offset < pending.size(); offset += 100) {
      var batch = pending.subList(offset, Math.min(offset + 100, pending.size()));
      var args = new ArrayList<Object>();
      for (String hash : batch) Collections.addAll(args, tenant, version, generation, model, hash);
      db.exec(
          "INSERT INTO index_cache_references(tenant_id,version_id,generation_id,model_identity,content_hash) VALUES "
              + String.join(",", Collections.nCopies(batch.size(), "(?,?,?,?,?)")),
          args.toArray());
    }
  }

  public boolean shared(String tenant, String version, String model, String hash) {
    return !db.list(
            "SELECT r.version_id FROM index_cache_references r JOIN document_versions v ON v.id=r.version_id AND v.tenant_id=r.tenant_id JOIN documents d ON d.id=v.document_id AND d.tenant_id=v.tenant_id JOIN knowledge_bases k ON k.id=d.kb_id AND k.tenant_id=d.tenant_id WHERE r.tenant_id=? AND r.version_id<>? AND r.model_identity=? AND r.content_hash=? AND d.status<>'DELETED' AND k.status<>'DELETED' LIMIT 1",
            tenant,
            version,
            model,
            hash)
        .isEmpty();
  }
}
