# 费率版本、导入预估与实际费用

企业 OWNER/ADMIN 在“套餐与用量”创建不可修改的费率版本，再按当前 revision 激活。支持 CNY/USD，单价保留最多 12 位小数。留空表示未定价，填写 0 才表示免费。初始没有费率；系统不会代填供应商报价。停用费率只影响之后创建的任务和请求。

## 核算范围

客户费用包含成功结算的查询次数 QUERY 和实际认领的处理任务 PROCESSING_TASK。解析、索引分别为一个任务，同一任务内部重试不会重复计算客户任务费。失败查询释放查询额度，但已经发生的模型消耗仍保留。

内部资源成本按实际记录计算：SOURCE_WRITE_BYTE 是新源文件对象的一次写入字节数；OCR_PAGE 是完成识别的页数；EMBEDDING_TOKEN、RERANK_TOKEN 和 GENERATION_INPUT_TOKEN/GENERATION_OUTPUT_TOKEN 使用模型返回的实际量。源文件复制草稿和重新处理不会重复收取写入字节费。这些是管理员配置的成本规则，不是供应商账单；不包含月存储租金、税、支付或外部发票。

任务和请求创建时冻结费率版本。修改当前费率不会重算旧执行的单价。后续到达的真实模型用量仍可补入原版本核算。历史执行没有费率快照时保持 UNCONFIGURED，不套用今天的费率。

结果分别提供 customer 与 provider。内部 provider 成本只向 OWNER/ADMIN 返回；应用凭证和其他角色只能看到获得授权执行的客户费用。单条请求、任务仍需通过原有租户和对象授权。

## 导入预估

选择文件后先查看预估，再点击“开始导入”。短 UTF-8 文本（txt/md/csv，最多 10000 字符）使用知识库已发布模型的原生 Tokenizer，加切片重叠比例估算 Embedding 量；结构扩展、缓存命中和实际解析可使结果不同。预估计划包含解析、索引两个任务。图片预估一页 OCR；PDF 页数及 OCR 文本、Office 文件和长文本的 Embedding 量在解析前未知。

预估不写入对象存储、不创建任务、不执行 OCR 或 Embedding。原生 Tokenizer 不可用时明确失败。接口返回 billing_revision，提交上传可携带该版本；价格变化返回 409 BILLING_RULE_CHANGED，用户重新预估。老客户端省略该参数时使用接收上传时的当前版本。预估不是锁价承诺，也不保证解析后产生相同费用。

## 公共 API

- GET/POST `/api/v1/billing/rules`：查看与创建版本，仅管理员。
- PUT `/api/v1/billing/active-rule`：`{rule_id, revision}`，rule_id 为 null 时停用。
- POST `/api/v1/knowledge-bases/{id}/import-estimate`：multipart file，需要知识库编辑权限。
- 上传文件或替换版本可携带 `billing_revision` 查询/表单参数。
- GET `/api/v1/usage/requests/{id}/cost`：企业管理员请求费用。
- GET `/api/v1/applications/{app}/requests/{id}/cost`：已授权应用请求费用。
- GET `/api/v1/jobs/{id}/cost`：已授权文档任务费用。

响应 basis 为 ESTIMATE、PENDING 或 ACTUAL。金额采用十进制字符串；COMPLETE 才返回完整 amount。未定价或用量未知时 amount 为 null，known_subtotal 仅表示已知部分，不能当作总费用。未知调用不按零消耗计。失败或未结束的 OCR 页保留未知部分。

## 升级

V33 新增费率表及执行快照列，不修改历史账单。先升级 Java，再使用预估 UI/SDK。回滚应用前需保留新增表列与数据；不要删除历史费率，否则无法解释原执行费用。恢复时应一并恢复数据库中的费率、执行和用量记录。
