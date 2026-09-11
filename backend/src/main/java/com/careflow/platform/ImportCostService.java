package com.careflow.platform;

import static com.careflow.platform.Db.*;

import com.careflow.platform.Identity.Actor;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Pre-import estimates never execute OCR or embedding and never create an upload/task. */
@Service
public class ImportCostService {
  private final Identity auth;
  private final BillingRulesService rules;
  private final CostAccountingService costs;
  private final KnowledgeConfigurationService configurations;
  private final WorkerClient worker;

  public ImportCostService(
      Identity auth,
      BillingRulesService rules,
      CostAccountingService costs,
      KnowledgeConfigurationService configurations,
      WorkerClient worker) {
    this.auth = auth;
    this.rules = rules;
    this.costs = costs;
    this.configurations = configurations;
    this.worker = worker;
  }

  public Object estimate(Actor actor, String authorization, String kb, MultipartFile file)
      throws Exception {
    var knowledge = auth.kb(actor, kb, "edit");
    if (!str(knowledge, "status").equals("ACTIVE")) throw ApiException.hidden();
    var data = UploadValidation.validate(file);
    String extension =
        data.name().substring(data.name().lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    Long tokens = null;
    String method = "UNKNOWN_UNTIL_PARSE";
    if (Set.of("txt", "md", "csv").contains(extension)) {
      String text = new String(data.bytes(), StandardCharsets.UTF_8);
      if (!text.isBlank()
          && text.length() <= 10000
          && !str(knowledge, "published_configuration").isBlank()) {
        String configuration = str(knowledge, "published_configuration");
        var runtime = configurations.runtime(actor.tenant(), configuration);
        var chunking = configurations.definition(actor.tenant(), configuration).chunking();
        var counted =
            worker.call(
                "/internal/v1/tokenize",
                Map.of(
                    "text",
                    text,
                    "model_configuration",
                    runtime.embedding(),
                    "model_tokenizer",
                    "provider",
                    "model_maximum",
                    chunking.model_maximum()));
        tokens =
            (long)
                Math.ceil(
                    num(counted, "model_token_count")
                        * (1.0
                            + chunking.overlap()
                                / (double) (chunking.target() - chunking.overlap())));
        method = "NATIVE_TOKENS_WITH_OVERLAP_ESTIMATE";
      }
    }
    Long pages;
    if (Set.of("png", "jpg", "jpeg").contains(extension)) pages = 1L;
    else if (extension.equals("pdf")) pages = null;
    else pages = 0L;
    var provider = new LinkedHashMap<String, CostCalculation.Quantity>();
    provider.put("SOURCE_WRITE_BYTE", CostCalculation.Quantity.known(data.bytes().length));
    provider.put("OCR_PAGE", new CostCalculation.Quantity(pages, pages == null));
    provider.put("EMBEDDING_TOKEN", new CostCalculation.Quantity(tokens, tokens == null));
    if (!actor.equals(auth.authenticate(authorization))) throw ApiException.hidden();
    auth.kb(actor, kb, "edit");
    var pricing = rules.snapshot(actor.tenant());
    String rule = str(pricing, "billing_rule_id");
    var result =
        costs.quote(
            actor,
            rule,
            Map.of("PROCESSING_TASK", CostCalculation.Quantity.known(2)),
            provider,
            "ESTIMATE");
    result.put("billing_revision", pricing.get("billing_revision"));
    result.put("file_digest", data.digest());
    result.put("source_bytes", data.bytes().length);
    result.put("estimated_embedding_tokens", tokens);
    result.put("estimated_ocr_pages", pages);
    result.put("method", method);
    result.put(
        "assumptions",
        List.of(
            "计划执行解析和索引两个任务；实际按执行的任务核算",
            "文本估算基于原生Tokenizer及重叠比例，结构扩展和缓存复用可能改变实际消耗",
            "PDF页与OCR识别文本、超过10000字符和Office文件的Embedding量在解析前未知",
            "预估不上传存储、不创建任务、不执行OCR或Embedding；规则可能在实际提交前改变"));
    return result;
  }
}
