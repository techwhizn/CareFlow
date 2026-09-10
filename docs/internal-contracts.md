# Java–Python内部V1契约

模型计算API为`/internal/v1`，请求必须有X-Internal-Token；任务回调还必须有X-Lease-Token。公共应用凭证不能调用内部接口。Python静态契约见internal-openapi.json；Java任务接口见openapi.json。

Java WorkerProtocolV1与Python protocol_v1定义召回、重排、生成、Token计数、任务领取和完成结构。WorkerClient只允许已知操作，调用前后验证类型/边界，拒绝空响应、缺失必需字段及非有限分数。返回ID仍由Java重新查权威文档内容和授权；协议校验不替代租户授权。

- recall：tenant_id、version_ids（≤10000）、query（≤4000）、mode、allow_degraded、expected_model_identity（原索引身份）；响应dense/bm25/fused（每路≤40，id/score）、degraded。
- rerank：query、candidates（≤40，id/content）、allow_degraded；响应results和degraded。降级分数可为null，不能冒充相关性达标。
- tokenize：text（≤10000），响应非负token_count。
- generate/stream：query、evidence（1～6，id/content）；application/x-ndjson UTF-8。每行是text、done:true或error三者之一；只有done成功，之后不再交付。单行最多65536字符，非法UTF-8、异常、空流、缺失done都失败。消费取消关闭连接。
- 任务claim返回id、lease_token、kind、tenant_id、version_id、filename、pdf_page_limit（1～500，旧响应默认500）；待索引chunks只传id/content，不带多余正文元数据。
- PARSE完成传chunks（1～50000）：source_text/content/location/token_count；INDEX完成传verified:true、model_identity、可选embedding_tokens。Java仍按任务类型验证，事务与租约决定是否接受。

请求超时默认90秒；错误映射为503安全错误，不透传供应商正文、密钥或内部异常详情。测试使用本地HTTP服务模拟错误与超时，不冒充真实模型联调。不存在的非流式generate旧调用方法已删除。

前端知识库列表、KnowledgeDetail、DocumentDetail和ChunkWorkspace分别维护自己的视图；主要路径和操作集中在features/knowledge/client.ts，仍复用api.ts的身份/错误处理与ui.tsx的数据状态组件。

## 模型配置隔离（V1-03 开发部分）

召回、重排、生成内部请求可携带 `model_configuration`：kind、base_url、model、revision、dimensions、api_key。Embedding 必须有不可变revision和dimensions；其他类型不带dimensions。Java负责授权、配置来源及目标地址许可，公共检索请求不能指定此内部对象。密钥只在已认证内部请求传递，禁止记录请求正文；Python对象repr及默认JSON序列化遮蔽密钥。

Python使用ContextVar按请求读取模型参数，不修改共享环境变量。生成器每次推进时单独进入/退出上下文，避免跨线程池yield留下上下文或令牌。未携带快照时仍读取部署配置；这兼容开发期旧调用，不代表已完成配置发布。

Java只在已授权、有效、已发布版本间收集实际model_identity，并将其作为expected_model_identity传给Worker。缺失/混合身份返回INDEX_CONFIGURATION_UNRESOLVED；Worker当前身份不一致返回503，禁止把切错集合解释为空知识。现阶段Java还未按知识库快照分组路由多模型；用户应保持原部署模型，不能把更改全局配置当迁移旧索引。密钥轮换不改变模型身份，模型地址/名称/修订/维度改变会改变身份。

下一步是知识库配置草稿/发布/回滚、任务持久化快照和按原模型分组查询，V1-03尚未完成。真实内部快照查询证据见 [记录](reports/v1-03-model-context.md)。
