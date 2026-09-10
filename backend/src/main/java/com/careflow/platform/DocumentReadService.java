package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import org.springframework.stereotype.Service;

@Service
public class DocumentReadService {
  private final Db db;
  private final Identity auth;
  private final BlobStore blobs;

  public DocumentReadService(Db db, Identity auth, BlobStore blobs) {
    this.db = db;
    this.auth = auth;
    this.blobs = blobs;
  }

  public Object documents(Actor actor, String id, int page) {
    auth.kb(actor, id, "read");
    return db
        .list(
            "SELECT * FROM documents WHERE tenant_id=? AND kb_id=? AND status<>'DELETED' ORDER BY created_at DESC LIMIT 50 OFFSET ?",
            actor.tenant(),
            id,
            Math.max(0, page) * 50)
        .stream()
        .filter(
            d -> {
              try {
                auth.document(actor, str(d, "id"), "read");
                return true;
              } catch (ApiException e) {
                return false;
              }
            })
        .toList();
  }

  public Object versions(Actor actor, String id) {
    auth.document(actor, id, "read");
    return db
        .list(
            "SELECT id,document_id,filename,state,revision,ever_published,model_identity,configuration_id,created_at FROM document_versions WHERE tenant_id=? AND document_id=? ORDER BY created_at DESC",
            actor.tenant(),
            id)
        .stream()
        .filter(
            v -> {
              try {
                auth.version(actor, str(v, "id"), "read");
                return true;
              } catch (ApiException e) {
                if (e.status != 404) throw e;
                return false;
              }
            })
        .toList();
  }

  public Object chunks(Actor actor, String id, int page) {
    auth.version(actor, id, "read");
    return db.list(
        "SELECT * FROM chunks WHERE tenant_id=? AND version_id=? ORDER BY ordinal_no LIMIT 100 OFFSET ?",
        actor.tenant(),
        id,
        Math.max(0, page) * 100);
  }

  public record SourceFile(String filename, byte[] content) {}

  public SourceFile download(Actor actor, String id) {
    var version = auth.version(actor, id, "download");
    return new SourceFile(str(version, "filename"), blobs.get(str(version, "object_key")));
  }
}
