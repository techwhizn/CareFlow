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
    client.publish(ID, ID, 3, "publish");
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
    assertEquals("upload", keys.get(2));
    assertTrue(bodies.get(2).contains("name=\"file\""));
    assertTrue(bodies.get(2).contains("合成资料"));
    assertTrue(bodies.get(6).contains("\"minimum_rerank_score\":0.5"));
  }

  @Test
  void streamingRequiresDoneAndPreservesUnicode() throws Exception {
    contentType = "text/event-stream;charset=UTF-8";
    response = "event:delta\r\ndata:{\"text\":\"你好\"}\r\n\r\nevent:done\ndata:{}\n\n";
    List<CareFlowClient.Event> events = new ArrayList<>();
    client.answer(new CareFlowClient.Query("test"), "stable", events::add);
    assertEquals(
        List.of("delta", "done"), events.stream().map(CareFlowClient.Event::name).toList());
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
}
