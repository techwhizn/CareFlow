package com.careflow.platform;

/** Lexically scoped correlation IDs; explicit propagation across asynchronous boundaries. */
public final class TraceContext implements AutoCloseable {
  private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
  private final String previous;

  private TraceContext(String id) {
    previous = CURRENT.get();
    CURRENT.set(id);
  }

  public static TraceContext use(String id) {
    return new TraceContext(java.util.UUID.fromString(id).toString());
  }

  public static String current() {
    return CURRENT.get();
  }

  @Override
  public void close() {
    if (previous == null) CURRENT.remove();
    else CURRENT.set(previous);
  }
}
