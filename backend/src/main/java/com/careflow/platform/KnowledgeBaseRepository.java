package com.careflow.platform;

import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class KnowledgeBaseRepository {
  private final Db db;

  public KnowledgeBaseRepository(Db db) {
    this.db = db;
  }

  public List<Map<String, Object>> list(String tenant) {
    return db.list(
        "SELECT * FROM knowledge_bases WHERE tenant_id=? AND status<>'DELETED' ORDER BY created_at DESC,id",
        tenant);
  }

  public void create(String tenant, String id, String name, String description, String owner) {
    db.exec(
        "INSERT INTO knowledge_bases(id,tenant_id,name,description,owner_id) VALUES(?,?,?,?,?)",
        id,
        tenant,
        name,
        description,
        owner);
  }

  public List<Map<String, Object>> owners(String tenant) {
    return db.list(
        "SELECT id,name,role FROM members WHERE tenant_id=? AND active=TRUE AND removed=FALSE AND role IN ('OWNER','ADMIN','KNOWLEDGE_MANAGER') ORDER BY name,id",
        tenant);
  }

  public int update(String tenant, String id, KnowledgeBaseService.Attributes input, String tags) {
    return db.exec(
        "UPDATE knowledge_bases SET name=?,description=?,language=?,tags_json=?,owner_id=?,revision=revision+1 WHERE tenant_id=? AND id=? AND revision=?",
        input.name().trim(),
        Objects.toString(input.description(), ""),
        input.language(),
        tags,
        input.owner_id(),
        tenant,
        id,
        input.revision());
  }

  public List<Map<String, Object>> documents(String tenant, String kb) {
    return db.list(
        "SELECT id,published_version FROM documents WHERE tenant_id=? AND kb_id=? AND status<>'DELETED'",
        tenant,
        kb);
  }

  public List<Map<String, Object>> versions(String tenant, String document) {
    return db.list(
        "SELECT id,object_key,size_bytes FROM document_versions WHERE tenant_id=? AND document_id=?",
        tenant,
        document);
  }

  public long effectiveChunks(String tenant, String version) {
    return Db.num(
        db.one(
            "SELECT COUNT(*) AS n FROM chunks WHERE tenant_id=? AND version_id=? AND enabled=TRUE",
            tenant,
            version),
        "n");
  }

  public long failedJobs(String tenant, String document) {
    return Db.num(
        db.one(
            "SELECT COUNT(*) AS n FROM jobs j JOIN document_versions v ON v.id=j.version_id AND v.tenant_id=j.tenant_id WHERE j.tenant_id=? AND v.document_id=? AND j.state='FAILED'",
            tenant,
            document),
        "n");
  }

  public List<Map<String, Object>> applications(String tenant, String kb) {
    return db.list(
        "SELECT a.id,a.name,a.published FROM applications a JOIN application_bindings b ON b.tenant_id=a.tenant_id AND b.application_id=a.id WHERE b.tenant_id=? AND b.kb_id=? ORDER BY a.name,a.id",
        tenant,
        kb);
  }
}
