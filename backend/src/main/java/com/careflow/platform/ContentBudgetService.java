package com.careflow.platform;

import static com.careflow.platform.Db.*;

import java.util.*;
import org.springframework.stereotype.Service;

/** Token validation runs before a mutation transaction and uses its captured processing config. */
@Service
public class ContentBudgetService {
  private final WorkerClient worker;
  private final KnowledgeConfigurationService configurations;

  public ContentBudgetService(WorkerClient worker, KnowledgeConfigurationService configurations) {
    this.worker = worker;
    this.configurations = configurations;
  }

  public List<Long> count(String tenant, String configuration, List<String> texts) {
    if (texts.isEmpty()) return List.of();
    var runtime = configuration.isBlank() ? null : configurations.runtime(tenant, configuration);
    int maximum = runtime == null ? 600 : runtime.chunking().maximum();
    List<Long> counts = new ArrayList<>();
    for (String text : texts) {
      if (text == null || text.isBlank() || text.length() > 10000)
        throw new ApiException(400, "INVALID_CONTENT", "内容不能为空或超过10000字符");
      Map<String, Object> input = new HashMap<>(Map.of("text", text));
      if (runtime != null) {
        input.put("model_configuration", runtime.embedding());
        input.put("model_tokenizer", runtime.chunking().model_tokenizer());
        input.put("model_maximum", runtime.chunking().model_maximum());
      }
      var result = worker.call("/internal/v1/tokenize", input);
      long tokens = num(result, "token_count");
      if (tokens > maximum) throw new ApiException(400, "CHUNK_TOO_LONG", "切片超过配置的Token上限，请缩短后保存");
      if (result.get("model_token_count") != null
          && num(result, "model_token_count") > num(result, "model_limit"))
        throw new ApiException(400, "MODEL_INPUT_TOO_LONG", "切片超过模型实际输入窗口，请拆分后保存");
      counts.add(tokens);
    }
    return counts;
  }
}
