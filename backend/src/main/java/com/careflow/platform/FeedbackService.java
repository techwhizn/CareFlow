package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeedbackService {
  public record Feedback(
      @NotNull @Pattern(regexp = "helpful|incorrect") String feedback,
      @Pattern(regexp = "WRONG_ANSWER|WRONG_SOURCE|MISSING_KNOWLEDGE|OUTDATED|OTHER") String reason,
      @Size(max = 2000) String comment,
      @Min(0) Long revision) {}

  private final Db db;
  private final Identity auth;
  private final AnswerHistoryService history;

  public FeedbackService(Db db, Identity auth, AnswerHistoryService history) {
    this.db = db;
    this.auth = auth;
    this.history = history;
  }

  @Transactional
  public Map<String, Object> save(Actor actor, String id, Feedback input) {
    auth.lock(actor);
    var answer = history.answer(actor, id);
    long revision = num(answer, "feedback_revision");
    if (input.revision() != null && input.revision() != revision) throw ApiException.conflict();
    String reason =
        "incorrect".equals(input.feedback()) ? Objects.toString(input.reason(), "OTHER") : "";
    String comment = Objects.toString(input.comment(), "").strip();
    db.exec(
        "UPDATE answers SET feedback=?,feedback_reason=?,feedback_comment=?,feedback_revision=feedback_revision+1 WHERE tenant_id=? AND id=?",
        input.feedback(),
        reason,
        comment,
        actor.tenant(),
        id);
    auth.audit(actor, "ANSWER_FEEDBACK", id, input.feedback());
    return Map.of(
        "feedback",
        input.feedback(),
        "reason",
        reason,
        "comment",
        comment,
        "revision",
        revision + 1);
  }
}
