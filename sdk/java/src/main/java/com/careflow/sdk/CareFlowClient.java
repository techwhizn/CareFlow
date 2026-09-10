package com.careflow.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Public API client. No automatic retries; redirects are never followed. */
public final class CareFlowClient {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final String base;
  private final String token;
  private final int timeoutMillis;

  public record Query(
      String query,
      String application_id,
      List<String> knowledge_base_ids,
      String mode,
      int limit,
      boolean debug,
      Double minimum_rerank_score,
      List<MetadataFilter> filters) {
    public Query(
        String query,
        String application_id,
        List<String> knowledge_base_ids,
        String mode,
        int limit,
        boolean debug,
        Double minimum_rerank_score) {
      this(
          query,
          application_id,
          knowledge_base_ids,
          mode,
          limit,
          debug,
          minimum_rerank_score,
          List.of());
    }

    public Query(String query) {
      this(query, null, List.of(), null, 6, false, null);
    }
  }

  public enum MetadataField {
    title,
    source,
    language,
    tags,
    product_models,
    valid_from,
    valid_until
  }

  public enum MetadataOperator {
    eq,
    in,
    contains,
    gte,
    lte
  }

  public record MetadataFilter(MetadataField field, MetadataOperator operator, Object value) {}

  public record Event(String name, JsonNode data) {}

  public static final class ApiException extends IOException {
    public final int status;
    public final String code;
    public final String requestId;

    ApiException(int status, String code, String requestId) {
      super("CareFlow request failed (HTTP " + status + ")");
      this.status = status;
      this.code = code;
      this.requestId = requestId;
    }
  }

  public CareFlowClient(URI origin, String token) {
    this(origin, token, 120000);
  }

  public CareFlowClient(URI origin, String token, int timeoutMillis) {
    if (!Set.of("http", "https").contains(Objects.toString(origin.getScheme(), ""))
        || origin.getHost() == null
        || origin.getUserInfo() != null
        || origin.getQuery() != null
        || origin.getFragment() != null
        || !Set.of("", "/").contains(origin.getPath()))
      throw new IllegalArgumentException("Expected HTTP(S) origin without credentials or path");
    if (token == null
        || token.isBlank()
        || token.contains("\r")
        || token.contains("\n")
        || timeoutMillis <= 0)
      throw new IllegalArgumentException("Token and positive timeout required");
    this.base = origin.toString().replaceAll("/$", "") + "/api/v1/";
    this.token = token;
    this.timeoutMillis = timeoutMillis;
  }

  private static String id(String value) {
    return UUID.fromString(value).toString();
  }

  private HttpURLConnection connection(String method, String path, String key) throws IOException {
    var connection = (HttpURLConnection) URI.create(base + path).toURL().openConnection();
    connection.setInstanceFollowRedirects(false);
    connection.setConnectTimeout(timeoutMillis);
    connection.setReadTimeout(timeoutMillis);
    connection.setRequestMethod(method);
    connection.setRequestProperty("Authorization", "Bearer " + token);
    if (method.equals("POST"))
      connection.setRequestProperty(
          "Idempotency-Key", key == null ? UUID.randomUUID().toString() : key);
    return connection;
  }

  private static void send(HttpURLConnection connection, Object body) throws IOException {
    if (body == null) return;
    byte[] encoded = JSON.writeValueAsBytes(body);
    connection.setDoOutput(true);
    connection.setFixedLengthStreamingMode(encoded.length);
    connection.setRequestProperty("Content-Type", "application/json");
    try (var out = connection.getOutputStream()) {
      out.write(encoded);
    }
  }

  private static void check(HttpURLConnection connection) throws IOException {
    int status = connection.getResponseCode();
    if (status >= 200 && status < 300) return;
    String code = "HTTP_ERROR", requestId = null;
    try (var input = connection.getErrorStream()) {
      if (input != null) {
        var error = JSON.readTree(input.readNBytes(65536));
        if (error != null) {
          code = error.path("code").asText(code);
          requestId = error.path("request_id").asText(null);
        }
      }
    } catch (IOException ignored) {
      /* Preserve HTTP failure without logging the raw response. */
    }
    throw new ApiException(status, code, requestId);
  }

  private static JsonNode response(HttpURLConnection connection) throws IOException {
    check(connection);
    try (var input = connection.getInputStream()) {
      return JSON.readTree(input);
    }
  }

  private JsonNode request(String method, String path, Object body, String key) throws IOException {
    var connection = connection(method, path, key);
    try {
      send(connection, body);
      return response(connection);
    } finally {
      connection.disconnect();
    }
  }

  public JsonNode knowledgeBases() throws IOException {
    return request("GET", "knowledge-bases", null, null);
  }

  public JsonNode createKnowledgeBase(String name, String description, String key)
      throws IOException {
    return request(
        "POST", "knowledge-bases", Map.of("name", name, "description", description), key);
  }

  public JsonNode knowledgeConfigurations(String knowledgeBaseId) throws IOException {
    return request("GET", "knowledge-bases/" + id(knowledgeBaseId) + "/configurations", null, null);
  }

  public JsonNode configurationModels(String knowledgeBaseId) throws IOException {
    return request(
        "GET", "knowledge-bases/" + id(knowledgeBaseId) + "/configuration-models", null, null);
  }

  public JsonNode createKnowledgeConfiguration(
      String knowledgeBaseId, KnowledgeConfiguration configuration, String key) throws IOException {
    return request(
        "POST", "knowledge-bases/" + id(knowledgeBaseId) + "/configurations", configuration, key);
  }

  public JsonNode configurationImpact(String knowledgeBaseId, String configurationId)
      throws IOException {
    return request(
        "GET",
        "knowledge-bases/"
            + id(knowledgeBaseId)
            + "/configurations/"
            + id(configurationId)
            + "/impact",
        null,
        null);
  }

  public JsonNode publishKnowledgeConfiguration(
      String knowledgeBaseId, String configurationId, long revision, String reason, String key)
      throws IOException {
    return request(
        "POST",
        "knowledge-bases/" + id(knowledgeBaseId) + "/configuration-publications",
        Map.of("configuration_id", id(configurationId), "revision", revision, "reason", reason),
        key);
  }

  public JsonNode reprocess(String versionId, String key) throws IOException {
    return request("POST", "document-versions/" + id(versionId) + "/reprocess", null, key);
  }

  public JsonNode bindConfiguration(String versionId, long revision, String key)
      throws IOException {
    return request(
        "POST",
        "document-versions/" + id(versionId) + "/configuration-binding",
        Map.of("revision", revision),
        key);
  }

  public JsonNode documentVersions(String documentId) throws IOException {
    return request("GET", "documents/" + id(documentId) + "/versions", null, null);
  }

  public record FaqInput(
      long revision, String question, List<String> alternatives, String answer, String reason) {}

  public JsonNode documentContexts(String versionId, int page) throws IOException {
    return request(
        "GET", "document-versions/" + id(versionId) + "/contexts?page=" + page, null, null);
  }

  public JsonNode documentContext(String versionId, String contextId) throws IOException {
    return request(
        "GET", "document-versions/" + id(versionId) + "/contexts/" + id(contextId), null, null);
  }

  public JsonNode saveFaq(String versionId, FaqInput input, String contextId, String key)
      throws IOException {
    String path = "document-versions/" + id(versionId) + "/faqs";
    if (contextId != null) path += "/" + id(contextId);
    return request(contextId == null ? "POST" : "PUT", path, input, key);
  }

  public JsonNode detachContext(
      String versionId, String contextId, long revision, String reason, String key)
      throws IOException {
    return request(
        "POST",
        "document-versions/" + id(versionId) + "/contexts/" + id(contextId) + "/detach",
        Map.of("revision", revision, "reason", reason),
        key);
  }

  public JsonNode job(String jobId) throws IOException {
    return request("GET", "jobs/" + id(jobId), null, null);
  }

  public JsonNode index(String versionId, String key) throws IOException {
    return request("POST", "document-versions/" + id(versionId) + "/index", null, key);
  }

  public JsonNode publish(
      String documentId, String versionId, long revision, long versionRevision, String key)
      throws IOException {
    return request(
        "POST",
        "documents/" + id(documentId) + "/publications",
        Map.of(
            "version_id", id(versionId), "revision", revision, "version_revision", versionRevision),
        key);
  }

  public JsonNode search(Query query, String key) throws IOException {
    return request("POST", "retrieval/search", query, key);
  }

  public JsonNode upload(String knowledgeBaseId, Path file, String key) throws IOException {
    String name = file.getFileName().toString();
    if (name.contains("\r") || name.contains("\n") || name.contains("\"") || name.contains("\\"))
      throw new IllegalArgumentException("Invalid multipart filename");
    String boundary = "careflow-" + UUID.randomUUID();
    var connection =
        connection("POST", "knowledge-bases/" + id(knowledgeBaseId) + "/documents", key);
    try {
      connection.setDoOutput(true);
      connection.setChunkedStreamingMode(8192);
      connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
      try (var out = connection.getOutputStream()) {
        out.write(
            ("--"
                    + boundary
                    + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                    + name
                    + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        Files.copy(file, out);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
      }
      return response(connection);
    } finally {
      connection.disconnect();
    }
  }

  /** Callback runs on the calling thread. Throw from it to stop and close the stream. */
  public void answer(Query query, String key, Consumer<Event> callback) throws IOException {
    var connection = connection("POST", "answers", key);
    try {
      connection.setRequestProperty("Accept", "text/event-stream");
      send(connection, query);
      check(connection);
      if (!Objects.toString(connection.getContentType(), "")
          .split(";", 2)[0]
          .trim()
          .equals("text/event-stream")) throw new IOException("Expected SSE response");
      try (var reader =
          new BufferedReader(
              new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
        String name = "message";
        var data = new StringBuilder();
        int size = 0;
        String line;
        while ((line = reader.readLine()) != null) {
          size += line.length();
          if (size > 1024 * 1024) throw new IOException("SSE event exceeds size limit");
          if (line.isEmpty()) {
            if (!data.isEmpty()) {
              JsonNode payload;
              try {
                payload = JSON.readTree(data.toString());
              } catch (IOException invalid) {
                throw new IOException("Invalid SSE JSON");
              }
              if (payload == null || !payload.isObject())
                throw new IOException("Invalid SSE payload");
              if (name.equals("error"))
                throw new IOException("Server reported an incomplete answer");
              callback.accept(new Event(name, payload));
              if (name.equals("done")) return;
            }
            name = "message";
            data.setLength(0);
            size = 0;
          } else {
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) value = value.substring(1);
            if (field.equals("event")) name = value;
            if (field.equals("data")) data.append(value).append('\n');
          }
        }
        throw new IOException("Connection ended before done");
      }
    } finally {
      connection.disconnect();
    }
  }
}
