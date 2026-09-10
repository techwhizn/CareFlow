package com.careflow.platform;

import static com.careflow.platform.Db.*;

import java.util.*;
import org.springframework.stereotype.Service;

/** Generation lifecycle operations run in the caller's tenant-locked task transaction. */
@Service
public class IndexGenerationService {
  private final Db db;
  private final KnowledgeConfigurationService configurations;
  private final IndexCacheReferences cache;

  public IndexGenerationService(
      Db db, KnowledgeConfigurationService configurations, IndexCacheReferences cache) {
    this.db = db;
    this.configurations = configurations;
    this.cache = cache;
  }

  public void recoverTerminalJobs() {
    db.exec(
        "UPDATE index_generations SET state='FAILED',completed_at=CURRENT_TIMESTAMP WHERE state='BUILDING' AND job_id IN (SELECT id FROM jobs WHERE state IN ('FAILED','CANCELLED','DONE'))");
  }

  public String start(Map<String, Object> job, Map<String, Object> version, String lease) {
    if (!str(job, "kind").equals("INDEX")) return null;
    String generation = id();
    var runtime = configurations.runtime(str(job, "tenant_id"), str(job, "configuration_id"));
    cache.register(
        str(job, "tenant_id"),
        str(job, "version_id"),
        configurations.modelIdentity(runtime.embedding()),
        generation);
    db.exec(
        "INSERT INTO index_generations(id,tenant_id,version_id,job_id,lease_token,configuration_id,model_identity,content_revision,previous_generation,state) VALUES(?,?,?,?,?,?,?,?,?,'BUILDING')",
        generation,
        str(job, "tenant_id"),
        str(job, "version_id"),
        str(job, "id"),
        lease,
        str(job, "configuration_id"),
        configurations.modelIdentity(runtime.embedding()),
        num(version, "revision"),
        version.get("active_index_generation"));
    db.exec("UPDATE jobs SET index_generation_id=? WHERE id=?", generation, str(job, "id"));
    return generation;
  }

  public void abandon(Map<String, Object> job, String state) {
    if (!Set.of("FAILED", "CANCELLED").contains(state)) throw new IllegalArgumentException();
    db.exec(
        "UPDATE index_generations SET state=?,completed_at=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=? AND state='BUILDING'",
        state,
        str(job, "index_generation_id"),
        str(job, "tenant_id"));
  }

  public void activate(Map<String, Object> job, String lease, Map<String, Object> body) {
    var generation =
        db.one(
            "SELECT * FROM index_generations WHERE id=? AND tenant_id=? AND job_id=? AND lease_token=? AND state='BUILDING' FOR UPDATE",
            str(job, "index_generation_id"),
            str(job, "tenant_id"),
            str(job, "id"),
            lease);
    var version =
        db.one(
            "SELECT * FROM document_versions WHERE tenant_id=? AND id=? FOR UPDATE",
            str(job, "tenant_id"),
            str(job, "version_id"));
    var chunks =
        db.list(
            "SELECT id,content FROM chunks WHERE tenant_id=? AND version_id=? AND enabled=TRUE",
            str(job, "tenant_id"),
            str(job, "version_id"));
    String manifest = IndexManifest.digest(IndexManifest.entries(chunks));
    if (!str(generation, "id").equals(str(body, "generation_id"))
        || !str(generation, "model_identity").equals(str(body, "model_identity"))
        || !manifest.equals(str(body, "manifest"))
        || num(generation, "content_revision") != num(version, "revision")
        || !str(generation, "previous_generation").equals(str(version, "active_index_generation")))
      throw new ApiException(409, "INDEX_GENERATION_MISMATCH", "索引代际、内容或前序版本已变化，未切换索引");
    db.exec(
        "UPDATE index_generations SET state='RETIRED',completed_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=? AND state='ACTIVE'",
        str(job, "tenant_id"),
        str(version, "active_index_generation"));
    db.exec(
        "UPDATE index_generations SET state='ACTIVE',manifest=?,chunk_count=?,completed_at=CURRENT_TIMESTAMP WHERE id=?",
        manifest,
        chunks.size(),
        str(generation, "id"));
    db.exec(
        "UPDATE document_versions SET active_index_generation=? WHERE tenant_id=? AND id=?",
        str(generation, "id"),
        str(job, "tenant_id"),
        str(job, "version_id"));
  }
}
