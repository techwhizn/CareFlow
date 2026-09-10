package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentUploadService {
  private final Db db;
  private final Identity auth;
  private final BlobStore blobs;

  public DocumentUploadService(Db db, Identity auth, BlobStore blobs) {
    this.db = db;
    this.auth = auth;
    this.blobs = blobs;
  }

  @Transactional
  public Object upload(
      Actor actor, String kb, String document, String requestKey, MultipartFile file)
      throws Exception {
    if (requestKey.isBlank() || requestKey.length() > 100) throw new IllegalArgumentException();
    auth.kb(actor, kb, "edit");
    if (document != null) auth.document(actor, document, "edit");
    var fileData = UploadValidation.validate(file);
    String name = fileData.name(), digest = fileData.digest();
    byte[] data = fileData.bytes();
    String fingerprint =
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(
                        (kb + ":" + Objects.toString(document, "new") + ":" + digest + ":" + name)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    auth.lock(actor);
    var existing =
        db.list(
            "SELECT j.id AS job_id,j.version_id,j.kind,j.upload_fingerprint,v.document_id,v.digest,v.filename,d.kb_id FROM jobs j JOIN document_versions v ON j.version_id=v.id JOIN documents d ON d.id=v.document_id WHERE j.tenant_id=? AND j.request_key=?",
            actor.tenant(),
            requestKey);
    if (!existing.isEmpty()) {
      var previous = existing.getFirst();
      auth.document(actor, str(previous, "document_id"), "edit");
      if (!fingerprint.equals(str(previous, "upload_fingerprint"))
          || !str(previous, "kind").equals("PARSE")
          || !str(previous, "kb_id").equals(kb)
          || !str(previous, "digest").equals(digest)
          || !str(previous, "filename").equals(name)
          || (document != null && !str(previous, "document_id").equals(document)))
        throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "同一请求键已用于不同文件或目标，请核对原请求");
      return Map.of(
          "job_id",
          str(previous, "job_id"),
          "version_id",
          str(previous, "version_id"),
          "document_id",
          str(previous, "document_id"));
    }
    if (document == null
        && !db.list(
                "SELECT v.id FROM document_versions v JOIN documents d ON d.id=v.document_id WHERE v.tenant_id=? AND d.kb_id=? AND d.status<>'DELETED' AND v.digest=?",
                actor.tenant(),
                kb,
                digest)
            .isEmpty()) throw new ApiException(409, "DUPLICATE_FILE", "知识库中已有相同内容，请使用版本更新");
    if (document == null) {
      document = id();
      db.exec(
          "INSERT INTO documents(id,tenant_id,kb_id,title) VALUES(?,?,?,?)",
          document,
          actor.tenant(),
          kb,
          name);
    }
    String version = id(),
        job = id(),
        key = actor.tenant() + "/" + document + "/" + version + "/source";
    blobs.put(key, data);
    db.exec(
        "INSERT INTO document_versions(id,tenant_id,document_id,object_key,filename,digest,size_bytes) VALUES(?,?,?,?,?,?,?)",
        version,
        actor.tenant(),
        document,
        key,
        name,
        digest,
        data.length);
    db.exec(
        "INSERT INTO jobs(id,tenant_id,version_id,kind,request_key,upload_fingerprint) VALUES(?,?,?,'PARSE',?,?)",
        job,
        actor.tenant(),
        version,
        requestKey,
        fingerprint);
    db.exec("INSERT INTO outbox(id,job_id) VALUES(?,?)", id(), job);
    auth.audit(actor, "DOCUMENT_UPLOAD", document, version);
    return Map.of("document_id", document, "version_id", version, "job_id", job);
  }
}
