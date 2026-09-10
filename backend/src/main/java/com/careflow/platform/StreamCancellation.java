package com.careflow.platform;

import java.io.Closeable;
import java.util.concurrent.atomic.*;

/** One request owns its upstream stream and worker thread; cancellation releases both. */
public final class StreamCancellation {
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final AtomicBoolean finished = new AtomicBoolean();
  private final AtomicReference<Closeable> upstream = new AtomicReference<>();
  private volatile Thread thread;

  public void bindThread() {
    thread = Thread.currentThread();
    check();
  }

  public void attach(Closeable stream) {
    upstream.set(stream);
    if (cancelled.get()) {
      closeUpstream();
      check();
    }
  }

  public void detach() {
    upstream.set(null);
  }

  public boolean active() {
    return !cancelled.get() && !finished.get();
  }

  public boolean cancelled() {
    return cancelled.get();
  }

  public void finish() {
    finished.set(true);
  }

  public void check() {
    if (cancelled.get()) throw new ApiException(409, "CANCELLED", "用户已停止接收");
  }

  public void cancel() {
    if (finished.get() || !cancelled.compareAndSet(false, true)) return;
    if (thread != null) thread.interrupt();
    closeUpstream();
  }

  private void closeUpstream() {
    var stream = upstream.getAndSet(null);
    if (stream != null)
      try {
        stream.close();
      } catch (java.io.IOException ignored) {
      }
  }
}
