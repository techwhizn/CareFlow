# 增量索引与处理用量

相同租户、相同Embedding地址/模型/不可变修订/维度、完全相同内容可复用向量。修改一条内容只计算新文本；修改标签或重新启用已有内容通常无需再次调用Embedding。不同租户、模型修订或端点不会复用；更改模型后需要实际重建。输入Token限制仍按当前冻结配置验证。

每个启用切片仍建立独立的dense和BM25记录，因此重复文本可能复用一次Embedding，但保留不同的来源、版本与权限。停用片段不传入本次索引；查询结果始终回到Java校验当前权限与启用状态。

`GET /api/v1/jobs/{id}`和任务列表新增：

- `indexed_chunks`：本次完成时写入的启用切片数。
- `embedded_texts`：本次完成租约实际发给Embedding的不同文本数。
- `reused_chunks`：未新增Embedding输入的切片数，包括本批相同文本复用。两者之和等于`indexed_chunks`。
- `index_usage.model_calls`：任务所有尝试登记的调用数。
- `index_usage.known_embedding_tokens`：供应商明确返回的Token小计。
- `index_usage.unknown_usage_calls`：没有确切Token的调用数，包括正在执行、断线未回报、供应商不返回用量。大于0时已知小计不是总费用。

零模型调用且全部复用时实际Embedding用量为0；旧任务没有计数和台账时无法追溯，不推断免费。Token化请求不计作Embedding请求；不以本地逻辑Token估算冒充供应商用量。Java/Python SDK现有任务查询均返回这些字段，任务中心展示同一台账摘要。

迁移V21后同步升级Java与Worker consumer。旧consumer没有完成计数，无法通过新Java的校验；先停止consumer，升级Java，再启动新consumer。进行中的调用失去回报时会保留未知；不要通过改成0消除告警。数据库备份必须包含`processing_model_calls`和`usage_events`。

实际索引核对、代际重建、缓存与孤立数据的保留期清理由后续工作包完成，不能将向量复用单独视为全部索引运维验收。
