import com.careflow.sdk.CareFlowClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Explicit live fixture check; ingest writes synthetic documents, answer calls real models. */
public class VerifyLifecycle {
  private static final class StopStream extends RuntimeException {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Choose ingest or answer");
    var client =
        new CareFlowClient(URI.create(required("CAREFLOW_URL")), required("CAREFLOW_TOKEN"));
    String kb = required("CAREFLOW_KB_ID");
    var report = new LinkedHashMap<String, Object>();
    report.put("language", "java");
    report.put("java_version", Runtime.version().toString());
    report.put("knowledge_base_id", kb);
    switch (args[0]) {
      case "ingest" -> {
        var uploaded = client.typed().upload(kb, Path.of(required("CAREFLOW_FILE")), key());
        waitForJob(client, uploaded.job_id());
        String index = client.index(uploaded.version_id(), key()).get("job_id").asText();
        waitForJob(client, index);
        var document =
            client.typed().documents(kb, 0).stream()
                .filter(row -> row.id().equals(uploaded.document_id()))
                .findFirst()
                .orElseThrow();
        var version =
            client.typed().documentVersions(document.id()).stream()
                .filter(row -> row.id().equals(uploaded.version_id()))
                .findFirst()
                .orElseThrow();
        client.publish(document.id(), version.id(), document.revision(), version.revision(), key());
        Path cancelled = Files.createTempFile("careflow-sdk-cancel-", ".md");
        try {
          Files.writeString(cancelled, "# Synthetic cancellation fixture\n" + key());
          var pending = client.typed().upload(kb, cancelled, key());
          client.cancelJob(pending.job_id());
          if (!client.typed().job(pending.job_id()).state().equals("CANCELLED"))
            throw new IllegalStateException("Job was not cancelled");
          report.put("cancelled_job_id", pending.job_id());
        } finally {
          Files.deleteIfExists(cancelled);
        }
        report.put("document_id", document.id());
        report.put("version_id", version.id());
        report.put("upload_parse_index_publish", "passed");
      }
      case "answer" -> {
        var query =
            new CareFlowClient.Query(
                required("CAREFLOW_QUERY"), null, List.of(kb), "hybrid", 6, true, null);
        var found = client.typed().search(query, key());
        if (found.evidence().isEmpty()) throw new IllegalStateException("No evidence");
        var events = new LinkedHashSet<String>();
        var content = new StringBuilder();
        client.answer(
            query,
            key(),
            event -> {
              events.add(event.name());
              if (event.name().equals("delta")) content.append(event.data().path("text").asText());
            });
        if (!events.containsAll(Set.of("delta", "citations", "done")))
          throw new IllegalStateException("Answer did not complete with citations");
        boolean[] stopped = {false};
        String[] cancelledRequest = {null};
        try {
          client.answer(
              query,
              key(),
              event -> {
                if (event.name().equals("start"))
                  cancelledRequest[0] = event.data().path("request_id").asText();
                if (event.name().equals("delta")) {
                  stopped[0] = true;
                  throw new StopStream();
                }
              });
        } catch (StopStream expected) {
          // The SDK closes the underlying connection in its finally block.
        }
        if (!stopped[0]) throw new IllegalStateException("No partial stream to cancel");
        if (cancelledRequest[0] == null) throw new IllegalStateException("No request ID");
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        String outcome;
        do {
          outcome = client.requestLog(null, cancelledRequest[0]).path("outcome").asText();
          if (!outcome.equals("RUNNING")) break;
          Thread.sleep(1000);
        } while (System.nanoTime() < deadline);
        if (!outcome.equals("CANCELLED"))
          throw new IllegalStateException("Server cancellation did not settle: " + outcome);
        report.put("cancelled_request_id", cancelledRequest[0]);
        report.put("server_status", outcome);
        report.put("search_trace_id", found.trace_id());
        report.put("events", events);
        report.put("answer_characters", content.length());
        report.put("stream_closed_after_first_delta", true);
      }
      default -> throw new IllegalArgumentException("Choose ingest or answer");
    }
    System.out.println(new ObjectMapper().writeValueAsString(report));
  }

  private static void waitForJob(CareFlowClient client, String id) throws Exception {
    long deadline = System.nanoTime() + java.time.Duration.ofMinutes(3).toNanos();
    while (System.nanoTime() < deadline) {
      var job = client.typed().job(id);
      if (job.state().equals("DONE")) return;
      if (Set.of("FAILED", "CANCELLED").contains(job.state()))
        throw new IllegalStateException("Job failed: " + job.error_code());
      Thread.sleep(1000);
    }
    throw new IllegalStateException("Job did not complete within three minutes");
  }

  private static String key() {
    return UUID.randomUUID().toString();
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
    return value;
  }
}
