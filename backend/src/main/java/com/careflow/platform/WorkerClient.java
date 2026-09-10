package com.careflow.platform;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class WorkerClient {
  private final RestClient client;

  public WorkerClient(
      @Value("${careflow.worker-url}") String url,
      @Value("${careflow.internal-token}") String token) {
    var f =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    f.setReadTimeout(Duration.ofSeconds(90));
    client =
        RestClient.builder()
            .baseUrl(url)
            .defaultHeader("X-Internal-Token", token)
            .requestFactory(f)
            .build();
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> call(String path, Object body) {
    try {
      var response = client.post().uri(path).body(body).retrieve().body(Map.class);
      if (response == null) throw new IllegalStateException();
      return response;
    } catch (Exception e) {
      throw new ApiException(503, "MODEL_OR_RETRIEVAL_UNAVAILABLE", "模型或检索服务不可用，请检查连接配置与任务日志");
    }
  }

  public void stream(Object body, java.util.function.Consumer<String> consumer) {
    client
        .post()
        .uri("/internal/v1/generate/stream")
        .body(body)
        .exchange(
            (request, response) -> {
              if (!response.getStatusCode().is2xxSuccessful())
                throw new ApiException(503, "GENERATION_UNAVAILABLE", "生成模型不可用");
              try (var reader =
                  new java.io.BufferedReader(
                      new java.io.InputStreamReader(
                          response.getBody(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                boolean done = false;
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                while ((line = reader.readLine()) != null) {
                  if (line.isBlank()) continue;
                  var item = mapper.readTree(line);
                  if (item.has("error"))
                    throw new ApiException(503, "GENERATION_UNAVAILABLE", "生成模型中断");
                  if (item.has("text")) consumer.accept(item.get("text").asText());
                  if (item.path("done").asBoolean()) done = true;
                }
                if (!done) throw new ApiException(503, "STREAM_INTERRUPTED", "生成未完成");
              }
              return null;
            });
  }
}
