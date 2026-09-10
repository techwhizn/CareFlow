package com.careflow.platform;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class WorkerClientContractTest {
  HttpServer server;
  ExecutorService executor;
  ValidatorFactory validation;
  WorkerClient client;
  volatile String response;
  volatile int status;
  volatile long delay;
  volatile String receivedToken;
  static final String TOKEN = "internal-synthetic-contract-secret-32chars";
  final ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void start() throws Exception {
    response = "{\"token_count\":3,\"model_token_count\":4,\"model_limit\":512}";
    status = 200;
    delay = 0;
    validation = Validation.buildDefaultValidatorFactory();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newCachedThreadPool();
    server.setExecutor(executor);
    server.createContext(
        "/",
        exchange -> {
          receivedToken = exchange.getRequestHeaders().getFirst("X-Internal-Token");
          exchange.getRequestBody().readAllBytes();
          try {
            if (delay > 0) Thread.sleep(delay);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange
              .getResponseHeaders()
              .set(
                  "Content-Type",
                  exchange.getRequestURI().getPath().endsWith("stream")
                      ? "application/x-ndjson"
                      : "application/json");
          try {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
          } finally {
            exchange.close();
          }
        });
    server.start();
    client = client(Duration.ofSeconds(2));
  }

  WorkerClient client(Duration timeout) {
    return new WorkerClient(
        "http://127.0.0.1:" + server.getAddress().getPort(),
        TOKEN,
        mapper,
        validation.getValidator(),
        timeout);
  }

  @AfterEach
  void close() {
    server.stop(0);
    executor.shutdownNow();
    validation.close();
  }

  @Test
  void sendsServiceIdentityAndValidatesTypedTokenResult() {
    assertThat(client.call("/internal/v1/tokenize", Map.of("text", "fixture")).get("token_count"))
        .isEqualTo(3);
    assertThat(receivedToken).isEqualTo(TOKEN);
    response = "{\"token_count\":3}";
    assertThatThrownBy(() -> client.call("/internal/v1/tokenize", Map.of("text", "fixture")))
        .isInstanceOf(ApiException.class);
    response = "{}";
    assertThatThrownBy(() -> client.call("/internal/v1/tokenize", Map.of("text", "fixture")))
        .isInstanceOf(ApiException.class);
    response = "{\"token_count\":-1}";
    assertThatThrownBy(() -> client.call("/internal/v1/tokenize", Map.of("text", "fixture")))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void faqInputBudgetFailureIsActionableAndDoesNotExposeWorkerBody() {
    status = 422;
    response = "{\"detail\":\"sensitive fixture\"}";
    var body =
        new WorkerProtocolV1.ManualFaqRequest(
            "question",
            List.of(),
            "answer",
            new KnowledgeConfiguration.Chunking(100, 200, 10),
            new WorkerProtocolV1.ModelConfiguration(
                "EMBEDDING", "https://model.invalid/v1", "fixture", "fixed", 512, ""));
    assertThatThrownBy(() -> client.call("/internal/v1/faq/chunk", body))
        .isInstanceOfSatisfying(
            ApiException.class,
            error -> {
              assertThat(error.status).isEqualTo(400);
              assertThat(error.code).isEqualTo("FAQ_CONTEXT_TOO_LONG");
              assertThat(error.getMessage()).doesNotContain("sensitive");
            });
  }

  @Test
  void emptyMalformedAndUpstreamErrorsAreSafeUnavailableErrors() {
    for (String body : List.of("", "not-json", "{\"token_count\":null}")) {
      response = body;
      assertThatThrownBy(() -> client.call("/internal/v1/tokenize", Map.of("text", "fixture")))
          .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(503));
    }
    response = "private-vendor-response";
    status = 401;
    assertThatThrownBy(() -> client.call("/internal/v1/tokenize", Map.of("text", "fixture")))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.status).isEqualTo(503);
              assertThat(e.getMessage()).doesNotContain("private-vendor");
            });
  }

  @Test
  void timeoutHasSameSafeMapping() {
    delay = 1000;
    var shortClient = client(Duration.ofMillis(100));
    assertThatThrownBy(() -> shortClient.call("/internal/v1/tokenize", Map.of("text", "fixture")))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(503));
  }

  Map<String, Object> generation() {
    return Map.of(
        "query",
        "fixture",
        "evidence",
        List.of(Map.of("id", "synthetic-chunk", "content", "Synthetic source")));
  }

  @Test
  void streamRequiresDoneAndStopsAtDone() {
    List<String> output = new ArrayList<>();
    response = "{\"text\":\"知识😀\"}\n{\"done\":true}\n{\"text\":\"must not deliver\"}\n";
    client.stream(generation(), output::add);
    assertThat(output).containsExactly("知识😀");
    response = "{\"text\":\"partial\"}\n";
    assertThatThrownBy(() -> client.stream(generation(), text -> {}))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code).isEqualTo("STREAM_INTERRUPTED"));
  }

  @Test
  void malformedOversizedEventsAndConsumerCancellationCloseStream() {
    for (String body :
        List.of(
            "{\"text\":123}\n",
            "invalid\n",
            "{\"error\":\"private-error\"}\n",
            "x".repeat(65537))) {
      response = body;
      assertThatThrownBy(() -> client.stream(generation(), text -> {}))
          .isInstanceOf(ApiException.class);
    }
    response = "{\"text\":\"fixture\"}\n{\"done\":true}\n";
    assertThatThrownBy(
            () ->
                client.stream(
                    generation(),
                    text -> {
                      throw new ApiException(409, "CANCELLED", "Stopped");
                    }))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code).isEqualTo("CANCELLED"));
  }

  @Test
  void usageEventsAreValidatedSeparatelyFromGeneratedText() {
    response =
        "{\"usage\":{\"input_tokens\":12,\"output_tokens\":4,\"total_tokens\":16}}\n{\"done\":true}\n";
    List<Map<String, Object>> usage = new ArrayList<>();
    client.stream(
        generation(),
        text -> fail("Usage is not answer text"),
        usage::add,
        new StreamCancellation());
    assertThat(usage).hasSize(1);
    assertThat(((Number) usage.getFirst().get("total_tokens")).longValue()).isEqualTo(16);
    response = "{\"usage\":{\"total_tokens\":-1}}\n{\"done\":true}\n";
    assertThatThrownBy(
            () -> client.stream(generation(), text -> {}, usage::add, new StreamCancellation()))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void cancellationInterruptsAStalledUpstreamWithoutWaitingForAnotherDelta() throws Exception {
    server.removeContext("/");
    server.createContext(
        "/",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(200, 0);
          try {
            exchange
                .getResponseBody()
                .write("{\"text\":\"first\"}\n".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            Thread.sleep(10000);
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    var received = new CountDownLatch(1);
    var cancellation = new StreamCancellation();
    var running =
        executor.submit(
            () -> {
              cancellation.bindThread();
              try {
                client(Duration.ofSeconds(30)).stream(
                    generation(), text -> received.countDown(), usage -> {}, cancellation);
                return "unexpected completion";
              } catch (ApiException error) {
                return error.code;
              }
            });
    assertThat(received.await(3, TimeUnit.SECONDS)).isTrue();
    cancellation.cancel();
    assertThat(running.get(2, TimeUnit.SECONDS)).isEqualTo("CANCELLED");
  }
}
