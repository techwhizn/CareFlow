import com.careflow.sdk.CareFlowClient;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Read-only live SDK check. All credentials are supplied through the environment. */
public class VerifyKnowledge {
  public static void main(String[] args) throws Exception {
    var client =
        new CareFlowClient(URI.create(required("CAREFLOW_URL")), required("CAREFLOW_TOKEN"));
    String kb = required("CAREFLOW_KB_ID"), version = required("CAREFLOW_VERSION_ID");
    if (!kb.equals(client.typed().knowledgeBase(kb).id()))
      throw new IllegalStateException("Knowledge base mismatch");
    if (client.typed().documents(kb, 0).isEmpty())
      throw new IllegalStateException("Expected populated fixture");
    var sink = new ByteArrayOutputStream();
    long bytes = client.management().downloadSource(version, sink);
    String sha =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sink.toByteArray()));
    String deniedToken = required("CAREFLOW_OTHER_TOKEN");
    var denied = new CareFlowClient(URI.create(required("CAREFLOW_URL")), deniedToken);
    int status = 0;
    try {
      denied.typed().knowledgeBase(kb);
    } catch (CareFlowClient.ApiException error) {
      status = error.status;
    }
    if (status != 404) throw new IllegalStateException("Cross-tenant request was not hidden");
    System.out.printf(
        "{\"language\":\"java\",\"source_bytes\":%d,\"source_sha256\":\"%s\",\"cross_tenant_status\":%d,\"java_version\":\"%s\"}%n",
        bytes, sha, status, Runtime.version());
  }

  private static String required(String key) {
    String value = System.getenv(key);
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
    return value;
  }
}
