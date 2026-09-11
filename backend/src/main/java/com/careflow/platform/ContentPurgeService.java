package com.careflow.platform;

import static com.careflow.platform.Db.*;

import java.util.*;
import org.springframework.stereotype.Service;

/** Content removal runs only after storage verification, inside a fenced cleanup transaction. */
@Service
public class ContentPurgeService {
  private final Db db;

  public ContentPurgeService(Db db) {
    this.db = db;
  }

  public void version(String tenant, String version) {
    db.exec(
        "DELETE FROM evaluation_dataset_versions WHERE tenant_id=? AND id IN (SELECT version_id FROM evaluation_evidence WHERE source_version_id=?)",
        tenant,
        version);
    db.exec(
        "DELETE FROM chunk_revisions WHERE tenant_id=? AND chunk_id IN (SELECT id FROM chunks WHERE tenant_id=? AND version_id=?)",
        tenant,
        tenant,
        version);
    db.exec(
        "UPDATE audit_events SET details='' WHERE tenant_id=? AND resource_id IN (SELECT id FROM chunks WHERE tenant_id=? AND version_id=?)",
        tenant,
        tenant,
        version);
    db.exec("DELETE FROM chunks WHERE tenant_id=? AND version_id=?", tenant, version);
    for (String table :
        List.of(
            "chunk_context_revisions",
            "chunk_contexts",
            "chunk_changes",
            "index_checks",
            "index_cache_references"))
      db.exec("DELETE FROM " + table + " WHERE tenant_id=? AND version_id=?", tenant, version);
    db.exec(
        "DELETE FROM content_conflicts WHERE tenant_id=? AND (version_id=? OR source_version_id=?)",
        tenant,
        version,
        version);
    db.exec(
        "UPDATE processing_model_calls SET lease_token='' WHERE tenant_id=? AND job_id IN (SELECT id FROM jobs WHERE tenant_id=? AND version_id=?)",
        tenant,
        tenant,
        version);
    db.exec(
        "DELETE FROM outbox WHERE job_id IN (SELECT id FROM jobs WHERE tenant_id=? AND version_id=?)",
        tenant,
        version);
    db.exec(
        "UPDATE jobs SET state='CANCELLED',lease_token=NULL,lease_until=NULL,dispatch_token=NULL,dispatch_until=NULL,configuration_id=NULL,request_key=CONCAT('purged:',id),upload_fingerprint=NULL WHERE tenant_id=? AND version_id=?",
        tenant,
        version);
    db.exec(
        "UPDATE index_generations SET state='PURGED',lease_token='',completed_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND version_id=?",
        tenant,
        version);
    db.exec(
        "UPDATE audit_events SET details='' WHERE tenant_id=? AND resource_id=?", tenant, version);
    db.exec(
        "UPDATE document_versions SET state='PURGED',object_key='',filename='',digest='',size_bytes=0,configuration_id=NULL,model_identity=NULL,active_index_generation=NULL,purged_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",
        tenant,
        version);
  }

  public void document(String tenant, String document) {
    db.exec(
        "DELETE FROM evaluation_dataset_versions WHERE tenant_id=? AND id IN (SELECT version_id FROM evaluation_evidence WHERE document_id=?)",
        tenant,
        document);
    db.exec(
        "DELETE FROM query_records WHERE tenant_id=? AND id IN (SELECT query_record_id FROM query_record_evidence WHERE document_id=?)",
        tenant,
        document);
    db.exec(
        "DELETE FROM integration_deliveries WHERE tenant_id=? AND resource_id=?", tenant, document);
    for (var answer :
        db.list(
            "SELECT DISTINCT a.id FROM answers a JOIN answer_evidence e ON e.answer_id=a.id WHERE a.tenant_id=? AND e.document_id=?",
            tenant,
            document)) {
      db.exec(
          "DELETE FROM integration_deliveries WHERE tenant_id=? AND resource_id=?",
          tenant,
          str(answer, "id"));
      db.exec("DELETE FROM answer_evidence WHERE answer_id=?", str(answer, "id"));
      db.exec("DELETE FROM answers WHERE tenant_id=? AND id=?", tenant, str(answer, "id"));
    }
    db.exec(
        "DELETE FROM document_metadata_history WHERE tenant_id=? AND document_id=?",
        tenant,
        document);
    db.exec("DELETE FROM permissions WHERE tenant_id=? AND resource_id=?", tenant, document);
    db.exec(
        "UPDATE audit_events SET details='' WHERE tenant_id=? AND resource_id=?", tenant, document);
    db.exec(
        "UPDATE documents SET status='DELETED',published_version=NULL,title='',source='',language='',tags_json=NULL,product_models_json=NULL,valid_from=NULL,valid_until=NULL,purged_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",
        tenant,
        document);
  }

  public void knowledgeBase(String tenant, String kb) {
    db.exec(
        "DELETE FROM integration_deliveries WHERE tenant_id=? AND resource_id IN (SELECT id FROM documents WHERE tenant_id=? AND kb_id=?)",
        tenant,
        tenant,
        kb);
    db.exec(
        "DELETE FROM integration_deliveries WHERE tenant_id=? AND resource_id IN (SELECT DISTINCT answer_id FROM answer_evidence WHERE document_id IN (SELECT id FROM documents WHERE tenant_id=? AND kb_id=?))",
        tenant,
        tenant,
        kb);
    db.exec("DELETE FROM evaluation_datasets WHERE tenant_id=? AND kb_id=?", tenant, kb);
    db.exec(
        "DELETE FROM query_records WHERE tenant_id=? AND id IN (SELECT query_record_id FROM improvement_tasks WHERE tenant_id=? AND kb_id=?)",
        tenant,
        tenant,
        kb);
    db.exec(
        "DELETE FROM query_records WHERE tenant_id=? AND knowledge_base_ids LIKE ?",
        tenant,
        "%\"" + kb + "\"%");
    db.exec("DELETE FROM improvement_tasks WHERE tenant_id=? AND kb_id=?", tenant, kb);
    db.exec("DELETE FROM permissions WHERE tenant_id=? AND resource_id=?", tenant, kb);
    db.exec("DELETE FROM application_bindings WHERE tenant_id=? AND kb_id=?", tenant, kb);
    db.exec(
        "DELETE FROM knowledge_configuration_publications WHERE tenant_id=? AND kb_id=?",
        tenant,
        kb);
    db.exec("DELETE FROM knowledge_configurations WHERE tenant_id=? AND kb_id=?", tenant, kb);
    db.exec("UPDATE audit_events SET details='' WHERE tenant_id=? AND resource_id=?", tenant, kb);
    db.exec(
        "UPDATE knowledge_bases SET name='',description='',language='',tags_json=NULL,published_configuration=NULL,purged_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",
        tenant,
        kb);
  }
}
