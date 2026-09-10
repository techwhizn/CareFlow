package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class PhysicalCleanupService {
  private final Db db;
  private final CleanupRepository repository;
  private final CleanupRootService roots;
  private final ContentPurgeService content;
  private final IndexCacheReferences cache;
  private final BlobStore blobs;
  private final WorkerClient worker;
  private final ObjectMapper json;
  private final boolean enabled;

  public PhysicalCleanupService(
      Db db,
      CleanupRepository repository,
      CleanupRootService roots,
      ContentPurgeService content,
      IndexCacheReferences cache,
      BlobStore blobs,
      WorkerClient worker,
      ObjectMapper json,
      @Value("${careflow.scheduling}") boolean enabled) {
    this.db = db;
    this.repository = repository;
    this.roots = roots;
    this.content = content;
    this.cache = cache;
    this.blobs = blobs;
    this.worker = worker;
    this.json = json;
    this.enabled = enabled;
  }

  @Scheduled(fixedDelay = 60000)
  public void seed() {
    if (enabled) repository.seed();
  }

  @Scheduled(fixedDelay = 10000)
  public void scheduled() {
    if (enabled) runOnce();
  }

  public void runOnce() {
    for (var row : repository.due()) processOne(str(row, "id"));
  }

  void processOne(String id) {
    var job = repository.claim(id);
    if (job == null) return;
    try {
      process(job);
    } catch (RuntimeException failure) {
      repository.failed(job);
    }
  }

  private String version(Map<String, Object> job) {
    return str(job, "resource_type").equals("INDEX_GENERATION")
        ? str(
            db.one(
                "SELECT version_id FROM index_generations WHERE tenant_id=? AND id=?",
                str(job, "tenant_id"),
                str(job, "resource_id")),
            "version_id")
        : str(job, "resource_id");
  }

  private void process(Map<String, Object> job) {
    String type = str(job, "resource_type");
    if (Set.of("KNOWLEDGE_BASE", "DOCUMENT").contains(type)) {
      roots.process(job);
      return;
    }
    String tenant = str(job, "tenant_id"), version = version(job);
    String generation = type.equals("INDEX_GENERATION") ? str(job, "resource_id") : null;
    switch (str(job, "phase")) {
      case "PREPARE" -> {
        var result =
            worker.call(
                "/internal/v1/index/purge-legacy-cache",
                new WorkerProtocolV1.LegacyCachePurge(tenant));
        requireVerified(result);
        repository.next(job, "VECTOR_DELETE", null, null, 0);
      }
      case "VECTOR_DELETE" -> {
        var result =
            worker.call(
                "/internal/v1/index/purge-version",
                new WorkerProtocolV1.IndexPurge(tenant, version, generation));
        requireVerified(result);
        repository.next(job, "VECTOR_COMPACTION", null, encode(result.get("compactions")), 0);
      }
      case "VECTOR_COMPACTION" -> {
        if (compacted(job)) repository.next(job, "CACHE", null, null, 0);
        else repository.next(job, "VECTOR_COMPACTION", null, str(job, "compactions_json"), 30);
      }
      case "CACHE" -> prepareCache(job, tenant, version, generation);
      case "CACHE_DELETE" -> {
        var payload = decode(str(job, "payload_json"));
        var entries =
            json.convertValue(
                payload.get("entries"),
                new TypeReference<List<WorkerProtocolV1.CacheReference>>() {});
        var result =
            worker.call(
                "/internal/v1/index/purge-cache", new WorkerProtocolV1.CachePurge(tenant, entries));
        requireVerified(result);
        repository.next(
            job,
            "CACHE_COMPACTION",
            str(job, "payload_json"),
            encode(result.get("compactions")),
            0);
      }
      case "CACHE_COMPACTION" -> {
        if (!compacted(job)) {
          repository.next(
              job, "CACHE_COMPACTION", str(job, "payload_json"), str(job, "compactions_json"), 30);
          return;
        }
        var refs =
            json.convertValue(
                decode(str(job, "payload_json")).get("references"),
                new TypeReference<List<WorkerProtocolV1.CacheReference>>() {});
        repository.fenced(
            job,
            () -> {
              for (var ref : refs) {
                var args =
                    new ArrayList<Object>(
                        List.of(tenant, version, ref.model_identity(), ref.content_hash()));
                if (generation != null) args.add(generation);
                db.exec(
                    "DELETE FROM index_cache_references WHERE tenant_id=? AND version_id=? AND model_identity=? AND content_hash=?"
                        + (generation == null ? "" : " AND generation_id=?"),
                    args.toArray());
              }
            });
        repository.next(job, "CACHE", null, null, 0);
      }
      case "OBJECT" -> {
        if (generation == null) purgeObject(job, tenant, version);
        repository.next(job, "CONTENT", null, null, 0);
      }
      case "CONTENT" ->
          repository.complete(
              job,
              () -> {
                if (generation == null) content.version(tenant, version);
                else
                  db.exec(
                      "UPDATE index_generations SET state='PURGED',lease_token='',completed_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND id=?",
                      tenant,
                      generation);
              });
      default -> throw new IllegalStateException("Unknown cleanup phase");
    }
  }

  private void prepareCache(
      Map<String, Object> job, String tenant, String version, String generation) {
    var args = new ArrayList<Object>(List.of(tenant, version));
    if (generation != null) args.add(generation);
    var refs =
        db.list(
            "SELECT model_identity,content_hash FROM index_cache_references WHERE tenant_id=? AND version_id=?"
                + (generation == null ? "" : " AND generation_id=?")
                + " ORDER BY model_identity,content_hash LIMIT 100",
            args.toArray());
    if (refs.isEmpty()) {
      repository.next(job, "OBJECT", null, null, 0);
      return;
    }
    var exclusive =
        refs.stream()
            .filter(
                ref ->
                    !cache.shared(
                        tenant, version, str(ref, "model_identity"), str(ref, "content_hash")))
            .toList();
    repository.next(
        job, "CACHE_DELETE", encode(Map.of("references", refs, "entries", exclusive)), null, 0);
  }

  private void purgeObject(Map<String, Object> job, String tenant, String version) {
    String key =
        str(
            db.one(
                "SELECT object_key FROM document_versions WHERE tenant_id=? AND id=?",
                tenant,
                version),
            "object_key");
    if (key.isBlank()) return;
    boolean shared =
        !db.list(
                "SELECT v.id FROM document_versions v JOIN documents d ON d.id=v.document_id AND d.tenant_id=v.tenant_id JOIN knowledge_bases k ON k.id=d.kb_id AND k.tenant_id=d.tenant_id WHERE v.tenant_id=? AND v.object_key=? AND d.status<>'DELETED' AND k.status<>'DELETED' LIMIT 1",
                tenant,
                key)
            .isEmpty();
    if (!shared) {
      blobs.purge(key);
      repository.fenced(
          job,
          () ->
              db.exec(
                  "DELETE FROM upload_staging WHERE tenant_id=? AND object_key=?", tenant, key));
    }
  }

  private void requireVerified(Map<String, Object> result) {
    if (!Boolean.TRUE.equals(result.get("verified"))
        || !(result.get("compactions") instanceof List<?>))
      throw new IllegalStateException("Storage purge not verified");
  }

  private boolean compacted(Map<String, Object> job) {
    var plans =
        json.convertValue(
            read(str(job, "compactions_json")),
            new TypeReference<List<WorkerProtocolV1.Compaction>>() {});
    var result =
        worker.call(
            "/internal/v1/index/compactions",
            new WorkerProtocolV1.CompactionRequest(str(job, "tenant_id"), plans));
    return Boolean.TRUE.equals(result.get("complete"));
  }

  private Object read(String value) {
    try {
      return json.readValue(value, Object.class);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid cleanup state", e);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> decode(String value) {
    return (Map<String, Object>) read(value);
  }

  private String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
