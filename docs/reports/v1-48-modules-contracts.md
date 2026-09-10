# V1-48 前端模块与内部契约验证

日期：2026-09-10。

Knowledge.tsx拆为知识库列表、KnowledgeDetail、DocumentDetail、ChunkWorkspace，主要路径与操作集中features/knowledge/client.ts。沿用公共api.ts身份/错误映射和ui.tsx状态组件，未引入新的前端依赖。

Java WorkerProtocolV1与Python protocol_v1为召回/重排/生成/Tokenize及任务领取/完成提供类型与边界。Java调用前后校验，Python请求拒绝额外字段；任务回调使用DTO并继续经过Java事务/租约校验。生成NDJSON明确事件类型，done后停止、空流/断流/格式错误失败；Java限制事件长度并严格解码UTF-8。删除未实现的旧非流式generate调用。

验证：Java Spotless/Maven verify 57项通过，包括新增5项真实本地HTTP服务契约测试（服务身份、缺字段、负计数、空/损坏响应、401安全映射、超时、Unicode、缺done、done后不交付、超长事件、消费取消）；Python37项通过、1项显式Milvus跳过，包括5项协议回归。Ruff与前端严格类型检查/构建通过。

真实Java+Python API、MySQL、MinIO、RabbitMQ链路验证类型化任务领取/完成，上传文本解析DONE；随后Java切片编辑调用Python真实Tokenize成功。浏览器经拆分后的文档列表进入详情和切片工作台，显示原文/清洗后内容，保存切片编辑成功。未调用真实Embedding/Rerank或把HTTP测试服务冒充模型供应商。

Java公共/内部OpenAPI与Python内部OpenAPI均已保存，契约说明见docs/internal-contracts.md。仍存在领域内部Map与通用Row；后续各业务功能继续细化，不把这次协议加固称为全部领域类型化。未推送或对外发布。
