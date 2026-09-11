package com.careflow.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.careflow.platform.Identity.Actor;
import java.util.*;
import org.junit.jupiter.api.Test;

class QualityFeedbackServiceTest {
  @Test
  void summaryContainsOnlyAggregates() {
    Db db = mock(Db.class);
    Identity auth = mock(Identity.class);
    when(db.list(anyString(), any(Object[].class)))
        .thenReturn(List.of(Map.of("key", "incorrect", "value", 2L)))
        .thenReturn(List.of(Map.of("key", "WRONG_SOURCE", "value", 1L)))
        .thenReturn(List.of(Map.of("key", "OPEN", "value", 1L)));
    var summary =
        new QualityFeedbackService(db, auth)
            .summary(new Actor("tenant", "member", "MEMBER", "OWNER"));
    assertThat(summary).containsKeys("answers", "reasons", "improvements", "generated_at");
    assertThat(summary.toString()).doesNotContain("question", "content");
  }
}
