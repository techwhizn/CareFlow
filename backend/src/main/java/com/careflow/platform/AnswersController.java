package com.careflow.platform;

import com.careflow.platform.Identity.Actor;
import com.careflow.platform.RetrievalService.Query;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
public class AnswersController {
  private final SearchService search;
  private final AnswerStreamService streams;
  private final AnswerHistoryService history;
  private final FeedbackService feedback;

  public AnswersController(
      SearchService search,
      AnswerStreamService streams,
      AnswerHistoryService history,
      FeedbackService feedback) {
    this.search = search;
    this.streams = streams;
    this.history = history;
    this.feedback = feedback;
  }

  @PostMapping("/retrieval/search")
  public Object search(
      @RequestAttribute Actor actor,
      @RequestHeader("Authorization") String authorization,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Query q) {
    return search.search(actor, authorization, key, q);
  }

  @PostMapping(value = "/answers", produces = "text/event-stream")
  public SseEmitter answer(
      @RequestAttribute Actor actor,
      @RequestHeader("Authorization") String authorization,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Query q) {
    return streams.answer(actor, authorization, key, q);
  }

  @GetMapping("/answers")
  public Object history(
      @RequestAttribute Actor actor, @RequestParam(required = false) String conversation_id) {
    return history.history(actor, conversation_id);
  }

  @GetMapping("/answers/{id}")
  public Object answer(@RequestAttribute Actor actor, @PathVariable UUID id) {
    return history.answer(actor, id.toString());
  }

  @PostMapping("/answers/{id}/feedback")
  public Object feedback(
      @RequestAttribute Actor actor,
      @PathVariable UUID id,
      @RequestBody @jakarta.validation.Valid FeedbackService.Feedback input) {
    return feedback.save(actor, id.toString(), input);
  }
}
