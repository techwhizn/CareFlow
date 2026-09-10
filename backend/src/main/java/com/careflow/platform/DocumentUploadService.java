package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentUploadService {
  private final BillingRulesService billing;
  private final Db db;
  private final Identity auth;
  private final BlobStore blobs;
  private final UploadStaging staging;
  private final TransactionTemplate tx;
  private final EntitlementService entitlements;
  private final ContentConflictService conflicts;

  public DocumentUploadService(
      BillingRulesService billing,
      Db db,
      Identity auth,
      BlobStore blobs,
      UploadStaging staging,
      TransactionTemplate tx,
      EntitlementService entitlements,
      ContentConflictService conflicts) {
    this.billing = billing;
    this.db = db;
    this.auth = auth;
    this.blobs = blobs;
    this.staging = staging;
    this.tx = tx;
    this.entitlements = entitlements;
    this.conflicts = conflicts;
  }

  private record Upload(
      Actor actor,
      String authorization,
      String kb,
      String document,
      String key,
      String name,
      String digest,
      String fingerprint,
      Long billingRevision) {
    @Override
    public String toString() {
      return "Upload[redacted]";
    }
  }

  public Object replace(
      Actor actor, String authorization, String document, String key, MultipartFile file)
      throws Exception {
    return replace(actor, authorization, document, key, file, null);
  }

  public Object replace(
      Actor actor,
      String authorization,
      String document,
      String key,
      MultipartFile file,
      Long billingRevision)
      throws Exception {
    var metadata = auth.document(actor, document, "edit");
    return upload(
        actor, authorization, str(metadata, "kb_id"), document, key, file, billingRevision);
  }

  public Object upload(
      Actor actor,
      String authorization,
      String kb,
      String document,
      String requestKey,
      MultipartFile file)
      throws Exception {
    return upload(actor, authorization, kb, document, requestKey, file, null);
  }

  public Object upload(
      Actor actor,
      String authorization,
      String kb,
      String document,
      String requestKey,
      MultipartFile file,
      Long billingRevision)
      throws Exception {
    if (requestKey.isBlank() || requestKey.length() > 100) throw new IllegalArgumentException();
    auth.kb(actor, kb, "edit");
    if (document != null) auth.document(actor, document, "edit");
    var data = UploadValidation.validate(file);
    String fingerprint =
        Identity.hash(
            kb + ":" + Objects.toString(document, "new") + ":" + data.digest() + ":" + data.name());
    Upload upload =
        new Upload(
            actor,
            authorization,
            kb,
            document,
            requestKey,
            data.name(),
            data.digest(),
            fingerprint,
            billingRevision);
    String nextDocument = document == null ? id() : document,
        version = id(),
        job = id(),
        stage = id();
    String objectKey = actor.tenant() + "/" + nextDocument + "/" + version + "/source";
    var previous =
        tx.execute(
            status -> {
              authorize(upload);
              var existing = existing(upload);
              if (existing != null) return existing;
              billing.checkRevision(actor.tenant(), upload.billingRevision());
              duplicate(upload);
              entitlements.upload(actor.tenant(), data.bytes().length);
              staging.reserve(stage, actor.tenant(), objectKey, data.bytes().length);
              return null;
            });
    if (previous != null) return previous;
    boolean attached = false;
    try {
      // Network I/O is deliberately outside both database transactions.
      blobs.put(objectKey, data.bytes());
      var result =
          tx.execute(
              status -> {
                authorize(upload);
                var existing = existing(upload);
                if (existing != null) return existing;
                billing.checkRevision(actor.tenant(), upload.billingRevision());
                duplicate(upload);
                entitlements.upload(actor.tenant(), 0);
                staging.attach(stage, actor.tenant());
                if (document == null)
                  db.exec(
                      "INSERT INTO documents(id,tenant_id,kb_id,title) VALUES(?,?,?,?)",
                      nextDocument,
                      actor.tenant(),
                      kb,
                      data.name());
                db.exec(
                    "INSERT INTO document_versions(id,tenant_id,document_id,object_key,filename,digest,size_bytes,configuration_id) VALUES(?,?,?,?,?,?,?,?)",
                    version,
                    actor.tenant(),
                    nextDocument,
                    objectKey,
                    data.name(),
                    data.digest(),
                    data.bytes().length,
                    auth.kb(actor, kb, "edit").get("published_configuration"));
                db.exec(
                    "INSERT INTO jobs(id,tenant_id,version_id,kind,request_key,upload_fingerprint,configuration_id) VALUES(?,?,?,'PARSE',?,?,?)",
                    job,
                    actor.tenant(),
                    version,
                    requestKey,
                    fingerprint,
                    auth.kb(actor, kb, "edit").get("published_configuration"));
                billing.attachJob(actor.tenant(), job, data.bytes().length);
                db.exec("INSERT INTO outbox(id,job_id) VALUES(?,?)", id(), job);
                if (document != null) conflicts.snapshotLatest(actor, nextDocument, version);
                auth.audit(actor, "DOCUMENT_UPLOAD", nextDocument, version);
                return Map.<String, Object>of(
                    "document_id", nextDocument, "version_id", version, "job_id", job);
              });
      attached = result != null && job.equals(str(result, "job_id"));
      return result;
    } finally {
      if (!attached) staging.abandon(stage);
    }
  }

  private void authorize(Upload upload) {
    auth.lock(upload.actor());
    if (!upload.actor().equals(auth.authenticate(upload.authorization())))
      throw ApiException.hidden();
    var kb = auth.kb(upload.actor(), upload.kb(), "edit");
    if (!str(kb, "status").equals("ACTIVE")) throw ApiException.hidden();
    if (upload.document() != null) auth.document(upload.actor(), upload.document(), "edit");
  }

  private Map<String, Object> existing(Upload upload) {
    var rows =
        db.list(
            "SELECT j.id AS job_id,j.version_id,j.kind,j.upload_fingerprint,v.document_id FROM jobs j JOIN document_versions v ON j.version_id=v.id AND j.tenant_id=v.tenant_id WHERE j.tenant_id=? AND j.request_key=?",
            upload.actor().tenant(),
            upload.key());
    if (rows.isEmpty()) return null;
    var previous = rows.getFirst();
    auth.document(upload.actor(), str(previous, "document_id"), "edit");
    if (!upload.fingerprint().equals(str(previous, "upload_fingerprint"))
        || !str(previous, "kind").equals("PARSE"))
      throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "同一请求键已用于不同文件或目标，请核对原请求");
    return Map.of(
        "job_id",
        str(previous, "job_id"),
        "version_id",
        str(previous, "version_id"),
        "document_id",
        str(previous, "document_id"));
  }

  private void duplicate(Upload upload) {
    if (upload.document() == null
        && !db.list(
                "SELECT v.id FROM document_versions v JOIN documents d ON d.id=v.document_id AND d.tenant_id=v.tenant_id WHERE v.tenant_id=? AND d.kb_id=? AND d.status<>'DELETED' AND v.digest=?",
                upload.actor().tenant(),
                upload.kb(),
                upload.digest())
            .isEmpty()) throw new ApiException(409, "DUPLICATE_FILE", "知识库中已有相同内容，请使用版本更新");
  }
}
