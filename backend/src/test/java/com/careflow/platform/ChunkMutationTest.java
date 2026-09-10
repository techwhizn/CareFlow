package com.careflow.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.careflow.platform.ChunkMutationService.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

class ChunkMutationTest extends ContentTestSupport {
  @Autowired ChunkMutationService mutations;
  @Autowired ContentConflictService conflicts;
  @Autowired DocumentDraftService drafts;

  @BeforeEach
  void tokenizer() {
    when(worker.call(eq("/internal/v1/tokenize"), any()))
        .thenReturn(Map.of("token_count", 10, "model_token_count", 10, "model_limit", 512));
  }

  List<Map<String, Object>> rows() {
    return db.list(
        "SELECT * FROM chunks WHERE tenant_id=? AND version_id=? ORDER BY ordinal_no",
        tenant,
        version);
  }

  List<Ref> refs(List<Map<String, Object>> rows) {
    return rows.stream().map(row -> new Ref(Db.str(row, "id"), Db.num(row, "revision"))).toList();
  }

  @Test
  void splitAndMergePreserveOrderSourcesAndHistory() {
    String first =
        chunk(0, "First step. Second step.", 10, true, "{\"type\":\"text\",\"start\":0}");
    chunk(1, "Next unchanged", 10, true, "{}");
    mutations.operate(
        actor,
        token,
        version,
        new Operation(
            0L, "SPLIT", List.of(new Ref(first, 0)), List.of(12), null, null, "split review"));
    var split = rows();
    assertThat(split).hasSize(3);
    assertThat(split.stream().map(row -> Db.str(row, "content")).toList())
        .containsExactly("First step. ", "Second step.", "Next unchanged");
    assertThat(Db.str(split.getFirst(), "location")).contains("manual_split").contains(first);
    mutations.operate(
        actor,
        token,
        version,
        new Operation(1L, "MERGE", refs(split.subList(0, 2)), null, null, null, "merge review"));
    var merged = rows();
    assertThat(merged).hasSize(2);
    assertThat(Db.str(merged.getFirst(), "content")).contains("First step.", "Second step.");
    assertThat(Db.str(merged.getLast(), "content")).isEqualTo("Next unchanged");
    assertThat(
            Db.num(
                db.one("SELECT COUNT(*) AS n FROM chunk_changes WHERE version_id=?", version), "n"))
        .isEqualTo(2);
  }

  @Test
  void invalidSplitBoundaryAndOversizeMergeAreAtomic() {
    String first = chunk(0, "A😀B", 10, true, "{}");
    String second = chunk(1, "second", 10, true, "{}");
    assertThatThrownBy(
            () ->
                mutations.operate(
                    actor,
                    token,
                    version,
                    new Operation(
                        0L,
                        "SPLIT",
                        List.of(new Ref(first, 0)),
                        List.of(2),
                        null,
                        null,
                        "surrogate")))
        .isInstanceOf(IllegalArgumentException.class);
    when(worker.call(eq("/internal/v1/tokenize"), any()))
        .thenReturn(Map.of("token_count", 601, "model_token_count", 601, "model_limit", 512));
    assertThatThrownBy(
            () ->
                mutations.operate(
                    actor,
                    token,
                    version,
                    new Operation(
                        0L,
                        "MERGE",
                        List.of(new Ref(first, 0), new Ref(second, 0)),
                        null,
                        null,
                        null,
                        "oversize")))
        .isInstanceOfSatisfying(
            ApiException.class, error -> assertThat(error.code).isEqualTo("CHUNK_TOO_LONG"));
    assertThat(rows()).hasSize(2);
    assertThat(
            Db.num(
                db.one("SELECT revision FROM document_versions WHERE id=?", version), "revision"))
        .isZero();
  }

  @Test
  void batchFlagsTagsAndDraftCopiesAreVersionFenced() {
    chunk(0, "first", 10, true, "{}");
    chunk(1, "second", 10, true, "{}");
    var initial = refs(rows());
    mutations.operate(
        actor,
        token,
        version,
        new Operation(
            0L, "TAGS", initial, null, null, List.of("checked", "support"), "tag review"));
    assertThatThrownBy(
            () ->
                mutations.operate(
                    actor,
                    token,
                    version,
                    new Operation(1L, "SET_ENABLED", initial, null, false, null, "stale")))
        .isInstanceOf(ApiException.class);
    assertThat(rows()).allMatch(row -> Db.bool(row, "enabled"));
    mutations.operate(
        actor,
        token,
        version,
        new Operation(1L, "SET_ENABLED", refs(rows()), null, false, null, "disable reviewed"));
    var copied = (Map<?, ?>) drafts.draft(actor, version);
    assertThat(db.list("SELECT * FROM chunks WHERE version_id=?", copied.get("id")))
        .allMatch(row -> !Db.bool(row, "enabled") && Db.str(row, "tags_json").contains("support"));
    verifyNoInteractions(worker);
  }

  @Test
  void credentialsRevokedDuringTokenizationCannotCommit() {
    String first = chunk(0, "first part second part", 10, true, "{}");
    when(worker.call(eq("/internal/v1/tokenize"), any()))
        .thenAnswer(
            invocation -> {
              db.exec("UPDATE credentials SET active=FALSE WHERE tenant_id=?", tenant);
              return Map.of("token_count", 10, "model_token_count", 10, "model_limit", 512);
            });
    assertThatThrownBy(
            () ->
                drafts.edit(
                    actor,
                    token,
                    first,
                    new DocumentDraftService.Edit("edited", true, 0, "review")))
        .isInstanceOf(ApiException.class);
    assertThat(Db.str(rows().getFirst(), "content")).isEqualTo("first part second part");
  }

  @Test
  void publishedVersionAndCrossVersionTargetsRejectMutations() {
    String first = chunk(0, "immutable", 10, true, "{}");
    db.exec("UPDATE document_versions SET ever_published=TRUE WHERE id=?", version);
    assertThatThrownBy(
            () ->
                mutations.operate(
                    actor,
                    token,
                    version,
                    new Operation(
                        0L,
                        "SET_ENABLED",
                        List.of(new Ref(first, 0)),
                        null,
                        false,
                        null,
                        "forbidden")))
        .isInstanceOfSatisfying(
            ApiException.class, error -> assertThat(error.code).isEqualTo("IMMUTABLE_PUBLICATION"));
    db.exec("UPDATE document_versions SET ever_published=FALSE WHERE id=?", version);
    assertThatThrownBy(
            () ->
                mutations.operate(
                    actor,
                    token,
                    version,
                    new Operation(
                        0L,
                        "TAGS",
                        List.of(new Ref(Db.id(), 0)),
                        null,
                        null,
                        List.of("a"),
                        "foreign")))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void frozenConflictBlocksIndexAndExplicitApplyUsesReviewedOldSnapshot() {
    String old = chunk(0, "Reviewed correction", 10, false, "{}");
    db.exec("UPDATE chunks SET origin='MANUAL_EDIT',tags_json='[\"reviewed\"]' WHERE id=?", old);
    String next = Db.id();
    var source = db.one("SELECT * FROM document_versions WHERE id=?", version);
    db.exec(
        "INSERT INTO document_versions(id,tenant_id,document_id,object_key,filename,digest,state) VALUES(?,?,?,'new','new.txt','new-digest','PARSED')",
        next,
        tenant,
        source.get("document_id"));
    conflicts.snapshot(actor, version, next);
    String target = Db.id();
    db.exec(
        "INSERT INTO chunks(id,tenant_id,version_id,ordinal_no,source_text,content,location,token_count) VALUES(?,?,?,0,'New original','New parse','{}',10)",
        target,
        tenant,
        next);
    assertThatThrownBy(() -> drafts.index(actor, next, "index-pending"))
        .isInstanceOfSatisfying(
            ApiException.class,
            error -> assertThat(error.code).isEqualTo("UNRESOLVED_CONTENT_CONFLICTS"));
    db.exec("UPDATE chunks SET content='Later unreviewed edit' WHERE id=?", old);
    String conflict =
        Db.str(db.one("SELECT id FROM content_conflicts WHERE version_id=?", next), "id");
    conflicts.resolve(
        actor,
        token,
        next,
        conflict,
        new ContentConflictService.Resolution(
            0L, "APPLY_TO_CHUNK", new Ref(target, 0), "apply observed"));
    var applied = db.one("SELECT * FROM chunks WHERE id=?", target);
    assertThat(Db.str(applied, "content")).isEqualTo("Reviewed correction");
    assertThat(Db.str(applied, "source_text")).isEqualTo("New original");
    assertThat(Db.bool(applied, "enabled")).isFalse();
    assertThat(Db.str(applied, "tags_json")).contains("reviewed");
    conflicts.requireResolved(actor, next);
    assertThatThrownBy(
            () ->
                conflicts.resolve(
                    actor,
                    token,
                    next,
                    conflict,
                    new ContentConflictService.Resolution(1L, "KEEP_NEW", null, "duplicate")))
        .isInstanceOf(ApiException.class);
  }
}
