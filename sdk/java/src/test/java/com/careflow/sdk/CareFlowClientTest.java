package com.careflow.sdk;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;

class CareFlowClientTest {
  HttpServer server;
  CareFlowClient client;
  List<String> paths;
  List<String> bodies;
  List<String> keys;
  int status;
  String response;
  String contentType;
  static final String ID = "00000000-0000-0000-0000-000000000001";

  @BeforeEach
  void setup() throws Exception {
    paths = new ArrayList<>();
    bodies = new ArrayList<>();
    keys = new ArrayList<>();
    status = 200;
    response = "{}";
    contentType = "application/json";
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          paths.add(exchange.getRequestURI().getPath());
          keys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          assertEquals("Bearer test-token", exchange.getRequestHeaders().getFirst("Authorization"));
          exchange.getResponseHeaders().set("Content-Type", contentType);
          exchange
              .getResponseHeaders()
              .set("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect");
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, bytes.length);
          try (var out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
    client =
        new CareFlowClient(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "test-token", 2000);
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void applicationPolicyKeepsModelRevisionsAndHistoryLimits() throws Exception {
    var input =
        new ApplicationConfiguration(
            List.of(ID),
            2,
            false,
            ID,
            new KnowledgeConfiguration.Retrieval("keyword", 3, null, false),
            new ApplicationConfiguration.Models(ID, 3, ID, 4),
            new ApplicationConfiguration.AnswerPolicy("en", "concise", 512, 1, 300));
    client.createApplicationConfiguration(ID, input);
    client.publishApplicationConfiguration(ID, ID, 2);
    assertTrue(bodies.get(0).contains("\"history_tokens\":300"));
    assertTrue(bodies.get(0).contains("\"generation_profile_revision\":4"));
    assertTrue(bodies.get(1).contains("\"revision\":2"));
  }

  @Test
  void feedbackAndImprovementRequestsPreserveObservedRevision() throws Exception {
    client.submitFeedback(ID, new CareFlowClient.Feedback("incorrect", "WRONG_SOURCE", "合成说明", 2));
    client.createImprovement(new CareFlowClient.ImprovementInput("ANSWER", ID, ID, ""));
    client.improvements();
    client.improvement(ID);
    client.improvementAssignees(ID);
    client.updateImprovement(ID, new CareFlowClient.ImprovementUpdate(4, "RESOLVED", null, "已修订"));
    assertTrue(bodies.get(0).contains("\"revision\":2"));
    assertEquals("/api/v1/improvements/" + ID + "/assignees", paths.get(4));
    assertTrue(bodies.get(5).contains("\"revision\":4"));
  }

  @Test
  void citationSourceUsesBothAuthorizedIdentities() throws Exception {
    client.citationSource(ID, ID);
    assertEquals("/api/v1/answers/" + ID + "/citations/" + ID, paths.getFirst());
  }

  @Test
  void conversationEndpointsAndQueryKeepTheExplicitSession() throws Exception {
    client.createConversation(null, List.of(ID));
    client.conversations();
    client.conversation(ID);
    client.answerHistory(ID);
    client.savedAnswer(ID);
    assertTrue(bodies.get(0).contains(ID));
    assertEquals("/api/v1/conversations/" + ID, paths.get(2));
    assertEquals("/api/v1/answers/" + ID, paths.get(4));
    var query =
        new CareFlowClient.Query("继续", null, List.of(), null, 6, false, null, List.of(), ID);
    assertEquals(ID, query.conversation_id());
    assertNull(new CareFlowClient.Query("兼容旧调用").conversation_id());
  }

  @Test
  void knowledgeConfigurationPreservesObservedRevisionsAndPublicReferences() throws Exception {
    var configuration =
        new KnowledgeConfiguration(
            "synthetic",
            new KnowledgeConfiguration.Parsing(20),
            new KnowledgeConfiguration.Chunking(120, 200, 10),
            new KnowledgeConfiguration.Retrieval("hybrid", 4, null, false),
            new KnowledgeConfiguration.Models(ID, ID, ID, 2, 3, 4));
    client.knowledgeConfigurations(ID);
    client.configurationModels(ID);
    client.createKnowledgeConfiguration(ID, configuration, "draft");
    client.configurationImpact(ID, ID);
    client.publishKnowledgeConfiguration(ID, ID, 7, "synthetic", "publish-config");
    client.reprocess(ID, "reprocess");
    assertTrue(bodies.get(2).contains("\"embedding_profile_revision\":2"));
    assertFalse(bodies.get(2).contains("api_key"));
    assertTrue(bodies.get(4).contains("\"revision\":7"));
    assertEquals("/api/v1/document-versions/" + ID + "/reprocess", paths.get(5));
    assertEquals("reprocess", keys.get(5));
    client.bindConfiguration(ID, 9, "bind");
    assertEquals("/api/v1/document-versions/" + ID + "/configuration-binding", paths.get(6));
    assertTrue(bodies.get(6).contains("\"revision\":9"));
    assertEquals("bind", keys.get(6));
  }

  @Test
  void metadataFilterSerializesWithoutAnExpressionString() throws Exception {
    var filter =
        new CareFlowClient.MetadataFilter(
            CareFlowClient.MetadataField.product_models,
            CareFlowClient.MetadataOperator.in,
            List.of("CF-100", "CF-200"));
    client.search(
        new CareFlowClient.Query(
            "fixture", null, List.of(), "keyword", 6, false, null, List.of(filter)),
        "filter");
    assertTrue(bodies.getFirst().contains("\"field\":\"product_models\""));
    assertTrue(bodies.getFirst().contains("\"value\":[\"CF-100\",\"CF-200\"]"));
  }

  @Test
  void faqAndContextRoutesPreserveObservedRevisionAndAlternatives() throws Exception {
    client.documentContexts(ID, 2);
    client.documentContext(ID, ID);
    var faq = new CareFlowClient.FaqInput(9, "question", List.of("similar"), "answer", "manual");
    client.saveFaq(ID, faq, null, "create-faq");
    client.saveFaq(ID, faq, ID, "update-faq");
    client.detachContext(ID, ID, 9, "separate", "detach");
    assertEquals("/api/v1/document-versions/" + ID + "/faqs", paths.get(2));
    assertEquals("/api/v1/document-versions/" + ID + "/faqs/" + ID, paths.get(3));
    assertTrue(bodies.get(2).contains("\"revision\":9"));
    assertTrue(bodies.get(3).contains("\"alternatives\":[\"similar\"]"));
    assertEquals("detach", keys.get(4));
  }

  @Test
  void qualityUsesVersionScopedReadRoute() throws Exception {
    client.documentQuality(ID, 2);
    assertEquals("/api/v1/document-versions/" + ID + "/quality", paths.getFirst());
  }

  @Test
  void chunkOperationsAndConflictResolutionKeepObservedRevisions() throws Exception {
    var ref = new CareFlowClient.ChunkRef(ID, 2);
    client.chunkOperation(
        ID,
        new CareFlowClient.ChunkOperation(
            3, "SPLIT", List.of(ref), List.of(8), null, null, "review"),
        "operation");
    client.resolveContentConflict(
        ID,
        ID,
        new CareFlowClient.ConflictResolution(3, "APPLY_TO_CHUNK", ref, "review"),
        "resolve");
    assertEquals("/api/v1/document-versions/" + ID + "/chunk-operations", paths.getFirst());
    assertTrue(bodies.getFirst().contains("\"split_offsets\":[8]"));
    assertTrue(bodies.get(1).contains("\"revision\":2"));
    assertEquals("resolve", keys.get(1));
  }

  @Test
  void documentVersionsPreservesContentRevision() throws Exception {
    response = "[{\"id\":\"" + ID + "\",\"revision\":7}]";
    assertEquals(7, client.documentVersions(ID).get(0).get("revision").asInt());
    assertEquals(List.of("/api/v1/documents/" + ID + "/versions"), paths);
  }

  @Test
  void publicApiRoutesAndMultipart() throws Exception {
    client.knowledgeBases();
    client.createKnowledgeBase("test", "description", "create");
    Path file = Files.createTempFile("careflow-test-", ".txt");
    try {
      Files.writeString(file, "合成资料");
      client.upload(ID, file, "upload");
    } finally {
      Files.deleteIfExists(file);
    }
    client.job(ID);
    client.index(ID, "index");
    client.publish(ID, ID, 3, 2, "publish");
    client.search(
        new CareFlowClient.Query("问题", null, List.of(), "hybrid", 6, false, .5), "search");
    assertEquals(
        List.of(
            "/api/v1/knowledge-bases",
            "/api/v1/knowledge-bases",
            "/api/v1/knowledge-bases/" + ID + "/documents",
            "/api/v1/jobs/" + ID,
            "/api/v1/document-versions/" + ID + "/index",
            "/api/v1/documents/" + ID + "/publications",
            "/api/v1/retrieval/search"),
        paths);
    assertTrue(bodies.get(5).contains("\"version_revision\":2"));
    assertEquals("upload", keys.get(2));
    assertTrue(bodies.get(2).contains("name=\"file\""));
    assertTrue(bodies.get(2).contains("合成资料"));
    assertTrue(bodies.get(6).contains("\"minimum_rerank_score\":0.5"));
  }

  @Test
  void streamingRequiresDoneAndPreservesUnicode() throws Exception {
    contentType = "text/event-stream;charset=UTF-8";
    response =
        "event:delta\r\ndata:{\"text\":\"你好\"}\r\n\r\nevent:usage\ndata:{\"total_tokens\":16}\n\nevent:done\ndata:{}\n\n";
    List<CareFlowClient.Event> events = new ArrayList<>();
    client.answer(new CareFlowClient.Query("test"), "stable", events::add);
    assertEquals(
        List.of("delta", "usage", "done"),
        events.stream().map(CareFlowClient.Event::name).toList());
    assertEquals("你好", events.getFirst().data().get("text").asText());
    for (String incomplete :
        List.of(
            "event:delta\ndata:{}\n\n",
            "event:error\ndata:{}\n\n",
            "event:done\ndata:{}",
            "event:delta\ndata:invalid\n\n")) {
      response = incomplete;
      assertThrows(
          IOException.class, () -> client.answer(new CareFlowClient.Query("test"), null, e -> {}));
    }
  }

  @Test
  void httpFailuresAreNotRetriedOrRedirected() {
    for (int code : List.of(401, 409, 429, 503, 302)) {
      status = code;
      response = "{\"code\":\"TEST\",\"request_id\":\"trace\",\"message\":\"private\"}";
      int before = paths.size();
      var error =
          assertThrows(
              CareFlowClient.ApiException.class,
              () -> client.search(new CareFlowClient.Query("test"), null));
      assertEquals(code, error.status);
      assertEquals(before + 1, paths.size());
      assertFalse(error.getMessage().contains("private"));
    }
  }

  @Test
  void callbackCanStopStream() {
    contentType = "text/event-stream";
    response = "event:delta\ndata:{}\n\nevent:done\ndata:{}\n\n";
    assertThrows(
        IllegalStateException.class,
        () ->
            client.answer(
                new CareFlowClient.Query("test"),
                null,
                e -> {
                  throw new IllegalStateException("stop");
                }));
  }

  @Test
  void generationMaintenancePreservesObservedRevisionAndGeneration() throws Exception {
    client.rebuildIndex(ID, new CareFlowClient.IndexRebuild(4, "repair", ID), "rebuild");
    client.checkIndex(ID, "check");
    client.indexHistory(ID);
    assertEquals("/api/v1/document-versions/" + ID + "/index/rebuild", paths.getFirst());
    assertTrue(bodies.getFirst().contains("\"expected_generation_id\":\"" + ID + "\""));
    assertTrue(bodies.getFirst().contains("\"revision\":4"));
    assertEquals("rebuild", keys.getFirst());
    assertTrue(paths.get(1).endsWith("/index/checks"));
    assertTrue(paths.get(2).endsWith("/index/history"));
  }

  @Test
  void cleanupMaintenanceContract() throws Exception {
    client.cleanupRequests();
    client.retryCleanup(ID, "storage recovered", "retry");
    assertEquals("/api/v1/cleanup-requests", paths.getFirst());
    assertEquals("/api/v1/cleanup-requests/" + ID + "/retry", paths.get(1));
    assertTrue(bodies.get(1).contains("storage recovered"));
    assertEquals("retry", keys.get(1));
  }

  @Test
  void operationsUsesPublicAuthenticatedRoute() throws Exception {
    client.operationsStatus();
    assertEquals(List.of("/api/v1/operations/status"), paths);
  }

  @Test
  void applicationLimitsPreserveRevisionAndNumericValues() throws Exception {
    client.applicationLimits(ID);
    client.updateApplicationLimits(ID, new CareFlowClient.ApplicationLimits(60, 4, 3));
    assertTrue(paths.get(0).endsWith("/limits"));
    assertTrue(bodies.get(1).contains("\"revision\":3"));
    assertTrue(bodies.get(1).contains("\"concurrent_requests\":4"));
  }

  @Test
  void requestLogsUseExplicitApplicationAndTenantRoutes() throws Exception {
    client.requestLogs(ID, ID);
    client.requestLog(ID, ID);
    client.requestLogs(null, null);
    assertEquals("/api/v1/applications/" + ID + "/requests", paths.get(0));
    assertEquals("/api/v1/applications/" + ID + "/requests/" + ID, paths.get(1));
    assertEquals("/api/v1/usage/requests", paths.get(2));
  }
}
