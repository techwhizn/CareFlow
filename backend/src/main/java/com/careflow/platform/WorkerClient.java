package com.careflow.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.io.*;
import java.net.http.HttpClient;
import java.nio.charset.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class WorkerClient {
  private final RestClient client;
  private final ObjectMapper mapper;
  private final Validator validator;

  @Autowired
  public WorkerClient(
      @Value("${careflow.worker-url}") String url,
      @Value("${careflow.internal-token}") String token,
      ObjectMapper mapper,
      Validator validator) {
    this(url, token, mapper, validator, Duration.ofSeconds(90));
  }

  WorkerClient(
      String url, String token, ObjectMapper mapper, Validator validator, Duration timeout) {
    this.mapper = mapper;
    this.validator = validator;
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    factory.setReadTimeout(timeout);
    client =
        RestClient.builder()
            .baseUrl(url)
            .defaultHeader("X-Internal-Token", token)
            .requestFactory(factory)
            .build();
  }

  private <T> T checked(Object value, Class<T> type) {
    if (value == null) throw new IllegalArgumentException("Empty worker response");
    T typed = type.isInstance(value) ? type.cast(value) : mapper.convertValue(value, type);
    if (!validator.validate(typed).isEmpty())
      throw new IllegalArgumentException("Invalid worker contract");
    return typed;
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> call(String path, Object body) {
    try {
      Object request;
      Class<?> response;
      switch (path) {
        case "/internal/v1/context/tokens" -> {
          request = checked(body, WorkerProtocolV1.ContextTokensRequest.class);
          response = WorkerProtocolV1.ContextTokensResponse.class;
        }
        case "/internal/v1/index/purge-version" -> {
          request = checked(body, WorkerProtocolV1.IndexPurge.class);
          response = WorkerProtocolV1.PurgeResponse.class;
        }
        case "/internal/v1/index/purge-cache" -> {
          request = checked(body, WorkerProtocolV1.CachePurge.class);
          response = WorkerProtocolV1.PurgeResponse.class;
        }
        case "/internal/v1/index/purge-legacy-cache" -> {
          request = checked(body, WorkerProtocolV1.LegacyCachePurge.class);
          response = WorkerProtocolV1.PurgeResponse.class;
        }
        case "/internal/v1/index/compactions" -> {
          request = checked(body, WorkerProtocolV1.CompactionRequest.class);
          response = WorkerProtocolV1.CompactionResponse.class;
        }
        case "/internal/v1/index/verify" -> {
          request = checked(body, WorkerProtocolV1.IndexVerification.class);
          response = WorkerProtocolV1.IndexVerificationResponse.class;
        }
        case "/internal/v1/recall" -> {
          request = checked(body, WorkerProtocolV1.RecallRequest.class);
          response = WorkerProtocolV1.RecallResponse.class;
        }
        case "/internal/v1/rerank" -> {
          request = checked(body, WorkerProtocolV1.RerankRequest.class);
          response = WorkerProtocolV1.RerankResponse.class;
        }
        case "/internal/v1/tokenize" -> {
          request = checked(body, WorkerProtocolV1.TokenizeRequest.class);
          response = WorkerProtocolV1.TokenizeResponse.class;
        }
        case "/internal/v1/faq/chunk" -> {
          request = checked(body, WorkerProtocolV1.ManualFaqRequest.class);
          response = WorkerProtocolV1.ParsedDocument.class;
        }
        default -> throw new IllegalArgumentException("Unknown worker operation");
      }
      var retrieval = client.post().uri(path).body(request).retrieve();
      if (path.equals("/internal/v1/faq/chunk"))
        retrieval.onStatus(
            status -> status.value() == 422,
            (sent, failed) -> {
              throw new ApiException(400, "FAQ_CONTEXT_TOO_LONG", "FAQ组或问题上下文超过配置预算，请缩短内容或拆为多个FAQ");
            });
      var result = retrieval.body(response);
      return mapper.convertValue(checked(result, response), Map.class);
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw unavailable();
    }
  }

  private static ApiException unavailable() {
    return new ApiException(503, "MODEL_OR_RETRIEVAL_UNAVAILABLE", "模型或检索服务不可用，请检查连接配置与任务日志");
  }

  public void stream(Object body, java.util.function.Consumer<String> consumer) {
    stream(body, consumer, ignored -> {}, new StreamCancellation());
  }

  public void stream(
      Object body,
      java.util.function.Consumer<String> consumer,
      java.util.function.Consumer<Map<String, Object>> usage,
      StreamCancellation cancellation) {
    cancellation.check();
    final WorkerProtocolV1.GenerateRequest request;
    try {
      request = checked(body, WorkerProtocolV1.GenerateRequest.class);
    } catch (Exception e) {
      throw unavailable();
    }
    try {
      client
          .post()
          .uri("/internal/v1/generate/stream")
          .body(request)
          .exchange(
              (sent, response) -> {
                if (!response.getStatusCode().is2xxSuccessful())
                  throw new ApiException(503, "GENERATION_UNAVAILABLE", "生成模型不可用");
                var decoder =
                    StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
                var input = response.getBody();
                cancellation.attach(input);
                try (var reader = new BufferedReader(new InputStreamReader(input, decoder))) {
                  String line;
                  while ((line = boundedLine(reader)) != null) {
                    if (line.isBlank()) continue;
                    cancellation.check();
                    var event = mapper.readTree(line);
                    if (!event.isObject() || event.has("error"))
                      throw new ApiException(503, "GENERATION_UNAVAILABLE", "生成模型中断");
                    if ((event.has("done") ? 1 : 0)
                            + (event.has("text") ? 1 : 0)
                            + (event.has("usage") ? 1 : 0)
                        != 1) throw new ApiException(503, "STREAM_INTERRUPTED", "生成事件类型冲突");
                    if (event.has("usage")) {
                      if (!event.get("usage").isObject())
                        throw new IllegalArgumentException("Invalid usage");
                      for (String field :
                          List.of("input_tokens", "output_tokens", "total_tokens")) {
                        var value = event.get("usage").get(field);
                        if (value != null
                            && !value.isNull()
                            && (!value.isIntegralNumber()
                                || !value.canConvertToLong()
                                || value.longValue() < 0))
                          throw new IllegalArgumentException("Invalid usage count");
                      }
                      var measured =
                          checked(
                              mapper.convertValue(event.get("usage"), Map.class),
                              WorkerProtocolV1.GenerationUsage.class);
                      usage.accept(mapper.convertValue(measured, Map.class));
                      continue;
                    }
                    if (event.path("done").isBoolean() && event.path("done").booleanValue())
                      return null;
                    if (!event.has("text") || !event.get("text").isTextual())
                      throw new ApiException(503, "STREAM_INTERRUPTED", "生成协议不完整");
                    consumer.accept(event.get("text").textValue());
                  }
                  throw new ApiException(503, "STREAM_INTERRUPTED", "生成未完成");
                }
              });
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      cancellation.check();
      throw new ApiException(503, "STREAM_INTERRUPTED", "生成连接中断");
    } finally {
      cancellation.detach();
    }
  }

  private static String boundedLine(Reader reader) throws IOException {
    StringBuilder line = new StringBuilder();
    int character;
    while ((character = reader.read()) != -1) {
      if (character == '\n') return line.toString();
      if (line.length() >= 65536) throw new IOException("Worker event exceeds line limit");
      line.append((char) character);
    }
    return line.isEmpty() ? null : line.toString();
  }
}
