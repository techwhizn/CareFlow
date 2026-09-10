package com.careflow.platform;

import java.util.*;
import java.util.regex.Pattern;

/** Deterministic whitespace normalization; never removes or invents model identifiers. */
public final class QueryProcessing {
  private QueryProcessing() {}

  public record Processed(
      String original, String rewritten, String method, List<String> identifiers) {}

  public static Processed conversation(String current, String previous) {
    var query = process(current);
    if (previous == null || previous.isBlank() || !query.identifiers().isEmpty()) return query;
    String combined = "当前问题：" + query.rewritten() + "\n历史问题：" + process(previous).rewritten();
    if (combined.length() > 4000)
      return new Processed(
          query.original(), query.rewritten(), "CURRENT_ONLY_LENGTH_LIMIT", query.identifiers());
    var rewritten = process(combined);
    return new Processed(
        query.original(), rewritten.rewritten(), "CONVERSATION_CONTEXT", rewritten.identifiers());
  }

  public static Processed process(String input) {
    if (input == null || input.length() > 4000) throw new IllegalArgumentException("Invalid query");
    var output = new StringBuilder();
    boolean space = false;
    for (int point : input.codePoints().toArray()) {
      if (Character.isWhitespace(point) || Character.isSpaceChar(point)) {
        space = output.length() > 0;
      } else {
        if (space) output.append(' ');
        output.appendCodePoint(point);
        space = false;
      }
    }
    String rewritten = output.toString();
    if (rewritten.isEmpty()) throw new IllegalArgumentException("Empty query");
    var identifiers = new LinkedHashSet<String>();
    var matcher = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]*").matcher(rewritten);
    while (matcher.find()) {
      String word = matcher.group();
      if (word.chars().anyMatch(Character::isDigit)) identifiers.add(word);
    }
    return new Processed(input, rewritten, "WHITESPACE_NORMALIZATION", List.copyOf(identifiers));
  }
}
