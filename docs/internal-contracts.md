# Java–Python内部V1契约

模型计算API为`/internal/v1`，请求必须有X-Internal-Token；任务回调还必须有X-Lease-Token。公共应用凭证不能调用内部接口。Python静态契约见internal-openapi.json；Java任务接口见openapi.json。

Java WorkerProtocolV1与Python protocol_v1定义召回、重排、生成、Token计数、任务领取和完成结构。WorkerClient只允许已知操作，调用前后验证类型/边界，拒绝空响应、缺失必需字段及非有限分数。返回ID仍由Java重新查权威文档内容和授权；协议校验不替代租户授权。

- recall：tenant_id、version_ids（≤10000）、query（≤4000）、mode、allow_degraded；响应dense/bm25/fused（每路≤40，id/score）、degraded。
- rerank：query、candidates（≤40，id/content）、allow_degraded；响应results和degraded。降级分数可为null，不能冒充相关性达标。
- tokenize：text（≤10000），响应非负token_count。
- generate/stream：query、evidence（1～6，id/content）；application/x-ndjson UTF-8。每行是text、done:true或error三者之一；只有done成功，之后不再交付。单行最多65536字符，非法UTF-8、异常、空流、缺失done都失败。消费取消关闭连接。
- 任务claim返回id、lease_token、kind、tenant_id、version_id、filename；待索引chunks只传id/content，不带多余正文元数据。
- PARSE完成传chunks（1～50000）：source_text/content/location/token_count；INDEX完成传verified:true、model_identity、可选embedding_tokens。Java仍按任务类型验证，事务与租约决定是否接受。

请求超时默认90秒；错误映射为503安全错误，不透传供应商正文、密钥或内部异常详情。测试使用本地HTTP服务模拟错误与超时，不冒充真实模型联调。不存在的非流式generate旧调用方法已删除。

前端知识库列表、KnowledgeDetail、DocumentDetail和ChunkWorkspace分别维护自己的视图；主要路径和操作集中在features/knowledge/client.ts，仍复用api.ts的身份/错误处理与ui.tsx的数据状态组件。
