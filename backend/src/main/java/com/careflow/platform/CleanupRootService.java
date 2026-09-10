package com.careflow.platform;

import static com.careflow.platform.Db.*;

import java.sql.Timestamp;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CleanupRootService {
  private final Db db;
  private final CleanupRepository repository;
  private final ContentPurgeService content;

  public CleanupRootService(Db db, CleanupRepository repository, ContentPurgeService content) {
    this.db = db;
    this.repository = repository;
    this.content = content;
  }

  public void process(Map<String, Object> job) {
    String tenant = str(job, "tenant_id"), resource = str(job, "resource_id");
    boolean kb = str(job, "resource_type").equals("KNOWLEDGE_BASE");
    boolean[] finished = {false};
    repository.fenced(
        job,
        () -> {
          var children =
              kb
                  ? db.list(
                      "SELECT d.id FROM documents d WHERE d.tenant_id=? AND d.kb_id=? AND d.purged_at IS NULL AND NOT EXISTS(SELECT 1 FROM cleanup_requests r WHERE r.tenant_id=d.tenant_id AND r.resource_type='DOCUMENT' AND r.resource_id=d.id) ORDER BY d.id LIMIT 100",
                      tenant,
                      resource)
                  : db.list(
                      "SELECT v.id FROM document_versions v WHERE v.tenant_id=? AND v.document_id=? AND v.purged_at IS NULL AND NOT EXISTS(SELECT 1 FROM cleanup_requests r WHERE r.tenant_id=v.tenant_id AND r.resource_type='DOCUMENT_VERSION' AND r.resource_id=v.id) ORDER BY v.id LIMIT 100",
                      tenant,
                      resource);
          if (!kb) {
            db.exec(
                "UPDATE documents SET status='DELETED',published_version=NULL WHERE tenant_id=? AND id=?",
                tenant,
                resource);
            db.exec(
                "UPDATE jobs SET state='CANCELLED',lease_token=NULL,lease_until=NULL WHERE tenant_id=? AND version_id IN (SELECT id FROM document_versions WHERE tenant_id=? AND document_id=?)",
                tenant,
                tenant,
                resource);
          }
          for (var child : children)
            repository.enqueue(
                tenant,
                kb ? "DOCUMENT" : "DOCUMENT_VERSION",
                str(child, "id"),
                str(job, "requested_by"),
                (Timestamp) job.get("not_before"),
                (Timestamp) job.get("deadline_at"));
          finished[0] =
              num(
                      db.one(
                          kb
                              ? "SELECT COUNT(*) AS n FROM documents WHERE tenant_id=? AND kb_id=? AND purged_at IS NULL"
                              : "SELECT COUNT(*) AS n FROM document_versions WHERE tenant_id=? AND document_id=? AND purged_at IS NULL",
                          tenant,
                          resource),
                      "n")
                  == 0;
        });
    if (finished[0])
      repository.complete(
          job,
          () -> {
            if (kb) content.knowledgeBase(tenant, resource);
            else content.document(tenant, resource);
          });
    else repository.next(job, "WAIT_CHILDREN", null, null, 30);
  }
}
