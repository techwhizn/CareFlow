# 模型实际用量

`GET /usage/models` 供企业所有者/管理员查看；`GET /applications/{id}/usage` 供本企业所有者、管理员、开发者以及该应用自身的READ凭证查看。其他应用和普通成员不能读取汇总。页面入口为“套餐与用量”和应用详情。

检索向量、重排以 `retrieval_model_calls` 关联公共 `query_record_id`（问答的 `start.request_id`），每次实际路由分别记录。跨不同索引配置检索会产生多次查询向量调用。账本在调用前写入 STARTED；拿到服务响应后写入 SUCCEEDED，失败写入 FAILED。这里的成功指 Worker 协议响应，不意味着模型评分或最终答案成功，需同时看 usage_state。

- REPORTED：供应商返回非负整数 Token；`known_tokens` 只汇总这些数值。
- NOT_REPORTED：调用返回但供应商未提供用量。
- UNKNOWN：调用结果或用量未能确认，包括进程中断仍在 STARTED 的记录。
- NOT_CALLED：关键词检索不做查询向量、空候选不调用重排。Token 保持 null。

生成用量与查询是否向客户成功交付分开保存。失败、取消、重试不将已知供应商成本清零。索引成本仍按任务实际批次统计，重用向量不伪造模型调用。共享知识库的索引和存储归企业，不向每个绑定应用重复计数。

汇总为当前保留账本的累计值；响应 coverage 标明检索自 V29、生成自 V24、索引自 V21 起记录，旧版本没有的数据不倒推。OCR和存储分项仍在V1-37后续增量中，当前不代表完整计费或发票。

V29只新增表及索引。先迁移Java再使用新版接口，原Worker协议无需升级。Worker调用失败前已消耗但未回报的Token无法精确恢复，保留UNKNOWN，不按文本长度估算。
