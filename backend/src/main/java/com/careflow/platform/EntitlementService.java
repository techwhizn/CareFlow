package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.constraints.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EntitlementService {
  public record PackageChange(
      @Min(0) @Max(100000) long member_limit,
      @Min(0) @Max(100000) long knowledge_base_limit,
      @Min(0) @Max(1000000000000000L) long storage_limit_bytes,
      @Min(0) @Max(1000000000) long processing_limit,
      @Min(0) @Max(1000000000000L) long query_limit,
      @Min(1) @Max(1000) int query_concurrency_limit,
      @Min(1) @Max(100) int task_concurrency_limit,
      @Min(1) @Max(500) int pdf_page_limit,
      @Min(1) @Max(100) int warning_percent,
      boolean active,
      OffsetDateTime starts_at,
      OffsetDateTime expires_at,
      @Min(0) long revision,
      @NotBlank @Size(max = 1000) String reason) {}

  public record Resource(long used, long limit, boolean warning) {}

  public record PackageConfiguration(
      long member_limit,
      long knowledge_base_limit,
      long storage_limit_bytes,
      long processing_limit,
      long query_limit,
      int query_concurrency_limit,
      int task_concurrency_limit,
      int pdf_page_limit,
      int warning_percent,
      boolean active,
      OffsetDateTime starts_at,
      OffsetDateTime expires_at,
      long revision) {}

  public record Snapshot(
      PackageConfiguration configuration,
      boolean available,
      Map<String, Resource> resources,
      long unknown_source_objects) {}

  private final Db db;
  private final Identity auth;

  public EntitlementService(Db db, Identity auth) {
    this.db = db;
    this.auth = auth;
  }

  private Map<String, Object> tenant(String tenant) {
    return db.one("SELECT * FROM tenants WHERE id=?", tenant);
  }

  private OffsetDateTime time(Map<String, Object> row, String key) {
    Object value = row.get(key);
    return value == null
        ? null
        : (value instanceof LocalDateTime local ? local : ((Timestamp) value).toLocalDateTime())
            .atOffset(ZoneOffset.UTC);
  }

  private boolean available(Map<String, Object> row) {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var start = time(row, "entitlement_starts");
    var end = time(row, "entitlement_expires");
    return bool(row, "entitlement_active")
        && (start == null || !now.isBefore(start))
        && (end == null || now.isBefore(end));
  }

  private Map<String, Object> active(String tenant) {
    var row = tenant(tenant);
    if (!available(row))
      throw new ApiException(403, "ENTITLEMENT_INACTIVE", "套餐尚未生效、已到期或已停用，请联系管理员");
    return row;
  }

  private long count(String sql, String tenant) {
    return num(db.one(sql, tenant), "n");
  }

  private long members(String tenant) {
    return count(
        "SELECT COUNT(*) AS n FROM members WHERE tenant_id=? AND active=TRUE AND removed=FALSE",
        tenant);
  }

  private long bases(String tenant) {
    return count(
        "SELECT COUNT(*) AS n FROM knowledge_bases WHERE tenant_id=? AND status<>'DELETED'",
        tenant);
  }

  private long storage(String tenant) {
    long used = 0;
    for (var object :
        db.list(
            "SELECT object_key,MAX(COALESCE(size_bytes,52428800)) AS bytes FROM document_versions WHERE tenant_id=? GROUP BY object_key",
            tenant)) used += num(object, "bytes");
    return used
        + num(
            db.one(
                "SELECT COALESCE(SUM(size_bytes),0) AS n FROM upload_staging WHERE tenant_id=? AND state IN ('PENDING','CLEANING')",
                tenant),
            "n");
  }

  private void capacity(long used, long additional, long limit, String resource) {
    if (additional > limit || used > limit - additional)
      throw new ApiException(429, "QUOTA_EXCEEDED", resource + "额度不足，请联系管理员");
  }

  public void newMember(String tenant) {
    var row = active(tenant);
    capacity(members(tenant), 1, num(row, "member_limit"), "成员");
  }

  public void newKnowledgeBase(String tenant) {
    var row = active(tenant);
    capacity(bases(tenant), 1, num(row, "knowledge_base_limit"), "知识库");
  }

  public void upload(String tenant, long bytes) {
    var row = active(tenant);
    capacity(storage(tenant), bytes, num(row, "storage_limit_bytes"), "文件空间");
  }

  public void query(String tenant) {
    var row = active(tenant);
    capacity(num(row, "queries_reserved"), 1, num(row, "query_concurrency_limit"), "查询并发");
  }

  public void checkTask(String tenant, Map<String, Object> job) {
    var row = active(tenant);
    capacity(
        count("SELECT COUNT(*) AS n FROM jobs WHERE tenant_id=? AND state='RUNNING'", tenant),
        1,
        num(row, "task_concurrency_limit"),
        "处理并发");
    if (!bool(job, "quota_counted"))
      capacity(num(row, "processing_used"), 1, num(row, "processing_limit"), "处理任务");
  }

  public void claimTask(String tenant, Map<String, Object> job) {
    checkTask(tenant, job);
    if (!bool(job, "quota_counted")) {
      db.exec("UPDATE tenants SET processing_used=processing_used+1 WHERE id=?", tenant);
      db.exec(
          "UPDATE jobs SET quota_counted=TRUE WHERE id=? AND tenant_id=?", str(job, "id"), tenant);
    }
  }

  private PackageConfiguration configuration(Map<String, Object> row) {
    return new PackageConfiguration(
        num(row, "member_limit"),
        num(row, "knowledge_base_limit"),
        num(row, "storage_limit_bytes"),
        num(row, "processing_limit"),
        num(row, "query_limit"),
        (int) num(row, "query_concurrency_limit"),
        (int) num(row, "task_concurrency_limit"),
        (int) num(row, "pdf_page_limit"),
        (int) num(row, "warning_percent"),
        bool(row, "entitlement_active"),
        time(row, "entitlement_starts"),
        time(row, "entitlement_expires"),
        num(row, "entitlement_revision"));
  }

  private Resource resource(long used, long limit, long threshold) {
    return new Resource(used, limit, limit == 0 || (double) used / limit * 100 >= threshold);
  }

  @Transactional
  public Snapshot snapshot(Actor actor) {
    auth.admin(actor);
    auth.lock(actor);
    var row = tenant(actor.tenant());
    long warning = num(row, "warning_percent");
    var resources = new LinkedHashMap<String, Resource>();
    resources.put("members", resource(members(actor.tenant()), num(row, "member_limit"), warning));
    resources.put(
        "knowledge_bases",
        resource(bases(actor.tenant()), num(row, "knowledge_base_limit"), warning));
    resources.put(
        "storage_bytes",
        resource(storage(actor.tenant()), num(row, "storage_limit_bytes"), warning));
    resources.put(
        "processing_tasks",
        resource(num(row, "processing_used"), num(row, "processing_limit"), warning));
    resources.put(
        "queries",
        resource(
            num(row, "queries_used") + num(row, "queries_reserved"),
            num(row, "query_limit"),
            warning));
    long unknown =
        count(
            "SELECT COUNT(DISTINCT object_key) AS n FROM document_versions WHERE tenant_id=? AND size_bytes IS NULL",
            actor.tenant());
    return new Snapshot(configuration(row), available(row), resources, unknown);
  }

  @Transactional
  public Snapshot change(Actor actor, PackageChange input) {
    auth.admin(actor);
    auth.lock(actor);
    var before = tenant(actor.tenant());
    if (input.starts_at() != null
        && input.expires_at() != null
        && !input.starts_at().isBefore(input.expires_at()))
      throw new IllegalArgumentException("Invalid entitlement period");
    for (var time :
        List.of(Optional.ofNullable(input.starts_at()), Optional.ofNullable(input.expires_at())))
      if (time.isPresent()) {
        int year = time.get().withOffsetSameInstant(ZoneOffset.UTC).getYear();
        if (year < 1000 || year > 9999) throw new IllegalArgumentException("Unsupported year");
      }
    if (db.exec(
            "UPDATE tenants SET member_limit=?,knowledge_base_limit=?,storage_limit_bytes=?,processing_limit=?,query_limit=?,query_concurrency_limit=?,task_concurrency_limit=?,pdf_page_limit=?,warning_percent=?,entitlement_active=?,entitlement_starts=?,entitlement_expires=?,entitlement_revision=entitlement_revision+1 WHERE id=? AND entitlement_revision=?",
            input.member_limit(),
            input.knowledge_base_limit(),
            input.storage_limit_bytes(),
            input.processing_limit(),
            input.query_limit(),
            input.query_concurrency_limit(),
            input.task_concurrency_limit(),
            input.pdf_page_limit(),
            input.warning_percent(),
            input.active(),
            input.starts_at() == null
                ? null
                : input.starts_at().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(),
            input.expires_at() == null
                ? null
                : input.expires_at().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(),
            actor.tenant(),
            input.revision())
        != 1) throw ApiException.conflict();
    auth.audit(
        actor,
        "ENTITLEMENT_UPDATE",
        actor.tenant(),
        "before=" + configuration(before) + ",after=" + input);
    return snapshot(actor);
  }
}
