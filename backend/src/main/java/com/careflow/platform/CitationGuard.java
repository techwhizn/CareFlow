package com.careflow.platform;

import java.util.*;

/** Holds bracketed citations across deltas until their full identifier is authorized. */
final class CitationGuard {
  private final Set<String> allowed;
  private final Set<String> cited = new LinkedHashSet<>();
  private final StringBuilder pending = new StringBuilder();
  private boolean bracket;

  CitationGuard(Collection<String> allowed) {
    this.allowed = Set.copyOf(allowed);
  }

  String accept(String delta) {
    StringBuilder output = new StringBuilder();
    for (char c : delta.toCharArray()) {
      if (!bracket) {
        if (c == '[') {
          bracket = true;
          pending.setLength(0);
        } else output.append(c);
      } else if (c == ']') {
        String id = pending.toString();
        if (!allowed.contains(id)) throw invalid();
        cited.add(id);
        output.append('[').append(id).append(']');
        bracket = false;
      } else {
        if (c == '[' || pending.length() >= 36) throw invalid();
        pending.append(c);
      }
    }
    return output.toString();
  }

  void finish() {
    if (bracket || cited.isEmpty()) throw invalid();
  }

  Set<String> cited() {
    return Set.copyOf(cited);
  }

  private ApiException invalid() {
    return new ApiException(502, "INVALID_CITATION", "模型未提供完整、合法的本次证据引用，回答未完成");
  }
}
