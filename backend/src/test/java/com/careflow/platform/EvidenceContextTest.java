package com.careflow.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

class EvidenceContextTest extends ContentTestSupport {
  @Autowired EvidenceContextService contexts;

  @BeforeEach
  void tokenResponse() {
    when(worker.call(eq("/internal/v1/context/tokens"), any()))
        .thenAnswer(
            call -> {
              var input = call.getArgument(1, WorkerProtocolV1.ContextTokensRequest.class);
              return Map.of(
                  "tokenizer",
                  "cl100k_base",
                  "counts",
                  input.candidates().stream()
                      .map(c -> Map.of("id", c.id(), "token_count", 8))
                      .toList());
            });
  }

  Map<String, Object> candidate(String id) {
    return db.one(
        "SELECT c.*,v.document_id FROM chunks c JOIN document_versions v ON v.id=c.version_id WHERE c.id=?",
        id);
  }

  String parent(String first, String second) {
    String id = Db.id();
    db.exec(
        "INSERT INTO chunk_contexts(id,tenant_id,version_id,ordinal_no,kind,source_text,content,location,token_count,alternatives_json) VALUES(?,?,?,0,'PARENT','original','Complete parent with both children','{}',8,'[]')",
        id,
        tenant,
        version);
    db.exec("UPDATE chunks SET context_id=? WHERE id IN (?,?)", id, first, second);
    return id;
  }

  @Test
  void siblingMatchesShareOneParentAndDisabledChildrenPreventParentExpansion() {
    String first = chunk(0, "first", 2, true, "{}"), second = chunk(1, "second", 2, true, "{}");
    parent(first, second);
    var expanded = contexts.prepare(actor, List.of(candidate(first), candidate(second)), () -> {});
    assertThat(expanded.get(first).get("content")).isEqualTo("Complete parent with both children");
    var selection =
        EvidenceSelection.select(
            expanded, List.of(Map.of("id", second), Map.of("id", first)), 6, null, false, false);
    assertThat(selection.evidence()).hasSize(1);
    assertThat(selection.excluded())
        .containsExactly(new EvidenceSelection.Exclusion(first, "DUPLICATE_CONTEXT"));
    db.exec("UPDATE chunks SET enabled=FALSE WHERE id=?", second);
    var disabled = contexts.prepare(actor, List.of(candidate(first)), () -> {});
    assertThat(disabled.get(first).get("content")).isEqualTo("first");
    assertThat(disabled.get(first).get("context_kind")).isEqualTo("CHUNK");
  }

  @Test
  void neighborsStayWithinVersionAndRemoveExactSourceOverlap() {
    String overlap = "an exact overlapping source passage";
    String first = chunk(0, "before " + overlap, 8, true, "{}");
    String selected = chunk(1, overlap + " after", 8, true, "{}");
    chunk(2, "disabled information", 8, false, "{}");
    String otherVersion = Db.id();
    String document =
        Db.str(
            db.one("SELECT document_id FROM document_versions WHERE id=?", version), "document_id");
    db.exec(
        "INSERT INTO document_versions(id,tenant_id,document_id,object_key,filename,digest,state) VALUES(?,?,?,'other','other.txt','digest','PARSED')",
        otherVersion,
        tenant,
        document);
    db.exec(
        "INSERT INTO chunks(id,tenant_id,version_id,ordinal_no,source_text,content,location,token_count) VALUES(?,?,?,0,'different version','must never expand','{}',4)",
        Db.id(),
        tenant,
        otherVersion);

    var expanded = contexts.prepare(actor, List.of(candidate(selected)), () -> {});
    assertThat(expanded.get(selected).get("content")).isEqualTo("before " + overlap + " after");
    assertThat(expanded.get(selected).get("covered_chunk_ids")).isEqualTo(List.of(first, selected));
    assertThat(expanded.get(selected).get("token_count")).isEqualTo(8L);
  }

  @Test
  void revocationBeforeTokenizerStopsDisclosureAndUnknownCountsCannotPass() {
    String selected = chunk(0, "fixture", 2, true, "{}");
    assertThatThrownBy(
            () ->
                contexts.prepare(
                    actor,
                    List.of(candidate(selected)),
                    () -> {
                      throw ApiException.hidden();
                    }))
        .isInstanceOf(ApiException.class);
    verify(worker, never()).call(eq("/internal/v1/context/tokens"), any());
    when(worker.call(eq("/internal/v1/context/tokens"), any()))
        .thenReturn(
            Map.of(
                "tokenizer",
                "cl100k_base",
                "counts",
                List.of(Map.of("id", Db.id(), "token_count", 8))));
    assertThatThrownBy(() -> contexts.prepare(actor, List.of(candidate(selected)), () -> {}))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void overlappingExpansionFallsBackToUncoveredMatchedChunk() {
    var a =
        new LinkedHashMap<String, Object>(
            Map.of(
                "id",
                "a",
                "document_id",
                "d",
                "content",
                "context a b",
                "token_count",
                5,
                "covered_chunk_ids",
                List.of("a", "b")));
    var c =
        new LinkedHashMap<String, Object>(
            Map.of(
                "id",
                "c",
                "document_id",
                "d",
                "content",
                "context b c",
                "token_count",
                5,
                "covered_chunk_ids",
                List.of("b", "c"),
                "matched_content",
                "only c",
                "matched_token_count",
                2));
    var result =
        EvidenceSelection.select(
            Map.of("a", a, "c", c),
            List.of(Map.of("id", "a"), Map.of("id", "c")),
            6,
            null,
            false,
            false);
    assertThat(result.evidence())
        .extracting(row -> row.get("content"))
        .containsExactly("context a b", "only c");
    assertThat(result.evidence().get(1).get("token_count")).isEqualTo(2L);
  }
}
