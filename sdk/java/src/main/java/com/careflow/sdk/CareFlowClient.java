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
      List<MetadataFilter> filters,
      String conversation_id) {
    public Query(
        String query,
        String application_id,
        List<String> knowledge_base_ids,
        String mode,
        int limit,
        boolean debug,
        Double minimum_rerank_score,
        List<MetadataFilter> filters) {
      this(
          query,
          application_id,
          knowledge_base_ids,
          mode,
          limit,
          debug,
          minimum_rerank_score,
          filters,
          null);
    }

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

  public JsonNode applications() throws IOException {
    return request("GET", "applications", null, null);
  }

  public JsonNode availableApplications() throws IOException {
    return request("GET", "applications/available", null, null);
  }

  public JsonNode applicationModels() throws IOException {
    return request("GET", "applications/model-options", null, null);
  }

  public JsonNode createApplication(String name, String description, String ownerId)
      throws IOException {
    var body = new java.util.LinkedHashMap<String, Object>();
    body.put("name", name);
    body.put("description", description);
    body.put("owner_id", ownerId);
    return request("POST", "applications", body, null);
  }

  public JsonNode applicationConfigurations(String app) throws IOException {
    return request("GET", "applications/" + id(app) + "/configurations", null, null);
  }

  public JsonNode createApplicationConfiguration(String app, ApplicationConfiguration input)
      throws IOException {
    return request("POST", "applications/" + id(app) + "/configurations", input, null);
  }

  public JsonNode publishApplicationConfiguration(String app, String configuration, long revision)
      throws IOException {
    return request(
        "POST",
        "applications/" + id(app) + "/configuration-publications",
        Map.of("configuration_id", id(configuration), "revision", revision),
        null);
  }

  public JsonNode applicationPublications(String app) throws IOException {
    return request("GET", "applications/" + id(app) + "/publications", null, null);
  }

  public JsonNode createConversation(String applicationId, List<String> knowledgeBaseIds)
      throws IOException {
    var body = new java.util.LinkedHashMap<String, Object>();
    body.put("application_id", applicationId);
    body.put("knowledge_base_ids", knowledgeBaseIds == null ? List.of() : knowledgeBaseIds);
    return request("POST", "conversations", body, null);
  }

  public JsonNode conversations() throws IOException {
    return request("GET", "conversations", null, null);
  }

  public JsonNode conversation(String conversationId) throws IOException {
    return request("GET", "conversations/" + id(conversationId), null, null);
  }

  public JsonNode answerHistory(String conversationId) throws IOException {
    return request(
        "GET",
        "answers" + (conversationId == null ? "" : "?conversation_id=" + id(conversationId)),
        null,
        null);
  }

  public record Feedback(String feedback, String reason, String comment, long revision) {}

  public record ImprovementInput(
      String source_kind, String source_id, String knowledge_base_id, String description) {}

  public record ImprovementUpdate(
      long revision, String state, String assignee_id, String resolution) {}

  public JsonNode submitFeedback(String answerId, Feedback input) throws IOException {
    return request("POST", "answers/" + id(answerId) + "/feedback", input, null);
  }

  public JsonNode createImprovement(ImprovementInput input) throws IOException {
    return request("POST", "improvements", input, null);
  }

  public JsonNode improvements() throws IOException {
    return request("GET", "improvements", null, null);
  }

  public JsonNode improvement(String taskId) throws IOException {
    return request("GET", "improvements/" + id(taskId), null, null);
  }

  public JsonNode improvementAssignees(String taskId) throws IOException {
    return request("GET", "improvements/" + id(taskId) + "/assignees", null, null);
  }

  public JsonNode updateImprovement(String taskId, ImprovementUpdate input) throws IOException {
    return request("PUT", "improvements/" + id(taskId), input, null);
  }

  public JsonNode citationSource(String answerId, String citationId) throws IOException {
    return request("GET", "answers/" + id(answerId) + "/citations/" + id(citationId), null, null);
  }

  public JsonNode savedAnswer(String answerId) throws IOException {
    return request("GET", "answers/" + id(answerId), null, null);
  }

  public JsonNode operationsStatus() throws IOException {
    return request("GET", "operations/status", null, null);
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

  public record ChunkRef(String id, long revision) {}

  public record ChunkOperation(
      long revision,
      String action,
      List<ChunkRef> chunks,
      List<Integer> split_offsets,
      Boolean enabled,
      List<String> tags,
      String reason) {}

  public record ConflictResolution(long revision, String action, ChunkRef target, String reason) {}

  public record ChunkEdit(String content, boolean enabled, long revision, String reason) {}

  public JsonNode editChunk(String chunkId, ChunkEdit input) throws IOException {
    return request("PUT", "chunks/" + id(chunkId), input, null);
  }

  public JsonNode documentChunks(String versionId, int page) throws IOException {
    return request(
        "GET", "document-versions/" + id(versionId) + "/chunks?page=" + page, null, null);
  }

  public JsonNode chunkOperation(String versionId, ChunkOperation input, String key)
      throws IOException {
    return request("POST", "document-versions/" + id(versionId) + "/chunk-operations", input, key);
  }

  public record IndexRebuild(long revision, String reason, String expected_generation_id) {}

  public JsonNode rebuildIndex(String versionId, IndexRebuild input, String key)
      throws IOException {
    return request("POST", "document-versions/" + id(versionId) + "/index/rebuild", input, key);
  }

  public JsonNode checkIndex(String versionId, String key) throws IOException {
    return request("POST", "document-versions/" + id(versionId) + "/index/checks", null, key);
  }

  public JsonNode cleanupRequests() throws IOException {
    return request("GET", "cleanup-requests", null, null);
  }

  public JsonNode retryCleanup(String requestId, String reason, String key) throws IOException {
    return request(
        "POST", "cleanup-requests/" + id(requestId) + "/retry", Map.of("reason", reason), key);
  }

  public JsonNode indexHistory(String versionId) throws IOException {
    return request("GET", "document-versions/" + id(versionId) + "/index/history", null, null);
  }

  public JsonNode contentConflicts(String versionId, int page) throws IOException {
    return request(
        "GET",
        "document-versions/" + id(versionId) + "/content-conflicts?page=" + page,
        null,
        null);
  }

  public JsonNode chunkChanges(String versionId, int page) throws IOException {
    return request(
        "GET", "document-versions/" + id(versionId) + "/chunk-changes?page=" + page, null, null);
  }

  public JsonNode resolveContentConflict(
      String versionId, String conflictId, ConflictResolution input, String key)
      throws IOException {
    return request(
        "POST",
        "document-versions/"
            + id(versionId)
            + "/content-conflicts/"
            + id(conflictId)
            + "/resolution",
        input,
        key);
  }

  public JsonNode documentContexts(String versionId, int page) throws IOException {
    return request(
        "GET", "document-versions/" + id(versionId) + "/contexts?page=" + page, null, null);
  }

  public JsonNode documentQuality(String versionId, int page) throws IOException {
    return request(
        "GET", "document-versions/" + id(versionId) + "/quality?page=" + page, null, null);
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
