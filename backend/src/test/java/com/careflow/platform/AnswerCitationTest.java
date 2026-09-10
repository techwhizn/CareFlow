package com.careflow.platform;

import static com.careflow.platform.Db.*;
import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class AnswerCitationTest extends ContentTestSupport {
  @Autowired AnswerHistoryService history;
  @Autowired AnswerCitationService citations;

  @Test
  void originalSnapshotIsImmutableAndEveryOpenReauthorizesAllDependencies() {
    db.exec("UPDATE document_versions SET ever_published=TRUE WHERE id=?", version);
    String first = chunk(0, "第一处原文", 4, true, "{\"page\":2}"),
        second = chunk(1, "第二处原文", 4, true, "{\"page\":3}");
    String doc =
        str(db.one("SELECT document_id FROM document_versions WHERE id=?", version), "document_id");
    var evidence =
        Map.<String, Object>of(
            "id",
            first,
            "document_id",
            doc,
            "version_id",
            version,
            "revision",
            0,
            "content",
            "合并证据",
            "covered_chunk_ids",
            List.of(first, second));
    var query = new RetrievalService.Query("合成问题", null, List.of(), null, 6, false, null);
    String answer = history.save(actor, query, "答案 [" + first + "]", List.of(evidence), null, null);
    db.exec("UPDATE chunks SET source_text='改变后的原文', revision=revision+1 WHERE id=?", first);
    var result = citations.source(actor, answer, first);
    assertThat(result.get("source_snapshot")).isEqualTo(true);
    assertThat(result.get("source_chunks").toString())
        .contains("第一处原文", "第二处原文")
        .doesNotContain("改变后的原文");
    assertThatThrownBy(() -> citations.source(actor, answer, second))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                citations.source(
                    new Identity.Actor(id(), actor.subject(), actor.kind(), actor.role()),
                    answer,
                    first))
        .isInstanceOf(ApiException.class);
    db.exec("UPDATE chunks SET enabled=FALSE WHERE id=?", second);
    assertThatThrownBy(() -> citations.source(actor, answer, first))
        .isInstanceOf(ApiException.class);
  }
}
