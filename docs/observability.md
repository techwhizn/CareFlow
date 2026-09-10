# 运行观测与数据保留

## 关联 ID

Java 每个 HTTP 请求生成 X-Request-ID。该值存入审计、上传/索引任务及查询账本的 http_request_id。查询执行自身使用 query_record_id（问答 start.request_id）关联召回、重排、生成、查询额度及费用；Java 调用 Worker 时将执行 ID 传入 X-Request-ID。Worker 按模板化操作、状态、耗时记录，不记录请求/响应正文或查询参数。

上传响应返回 job_id，任务行关联最初 HTTP ID；Consumer 以 job_id 记录领取、完成和失败，并在内部调用传 X-Correlation-ID。内部凭证校验后才接受格式合法的关联 ID。模型调用及 OCR 回执通过 job_id/调用 ID 连接实际用量。外部客户端不能指定 Java HTTP ID，也不能通过 ID 改变权限。

Java HTTP 日志只含 ID、方法、状态、耗时。SSE 的 HTTP 耗时表示异步交付开始，完整执行耗时应使用请求账本的 created_at/completed_at。错误日志不含异常正文；不能开启请求正文、HTTP wire 或供应商响应 DEBUG 日志。文件名、问题、答案、Token 密钥不作为指标标签。

## 健康与告警

- `/health/live`：进程存活。
- `/health/ready`（兼容 `/actuator/health`）：数据库可用返回200，否则503。scope=DATABASE，不代表外部模型、Milvus或队列都正常。
- Worker `/internal/v1/health`：需内部凭证，检测 Worker 进程；不调用模型。
- `/api/v1/operations/metrics`：当前企业 gauges 与 alerts；仅 OWNER/ADMIN/OPS。
- `/api/v1/operations/prometheus`：同一企业指标，Prometheus 文本格式，需要成员 Bearer 凭证。不同企业使用不同采集凭证，不允许应用凭证读取。

运行状态页面显示排队/运行/失败任务、过期租约、查询预占、过期查询和近五分钟失败查询。过期租约、过期预占或受阻清理数量大于0产生 warning；五分钟失败查询达到5次产生 QUERY_FAILURE_BURST。外部监控可定时采集并接入部署方告警渠道；仓库不主动发送邮件或部署监控服务。指标是数据库当前状态，不将重启后内存计数冒充累计值。

排查顺序：先按HTTP ID找到执行/任务ID，再查同ID的Worker日志、用量和固定错误码。依赖恢复后检查告警消失、租约重试及预占恢复。健康探测不发付费模型请求。

## 调试正文与审计保留

企业管理员通过 `/api/v1/retention` GET/PUT 或套餐页面配置 debug_body_collection、debug_retention_days（默认30）、audit_retention_days（默认180），修改带 revision。天数允许1–3650，修改保留审计。关闭正文采集后新 query_records.question 为空并标记 DISABLED。

后台每五分钟按企业锁处理一批最多100条到期调试问题正文和100条审计元数据。过期问题置空，body_state=EXPIRED；关闭采集时既有问题逐批置空，保留依赖、配置和用量元数据。重复扫描幂等。大批历史数据需要多个周期完成；应结合日志中的 debug_removed/audit_removed 核对清理进度。

此策略控制调试问题的额外副本。问答历史、会话、知识正文、反馈和改进任务属于产品数据；它们仍按原有授权与显式删除流程管理，不因调试保留期自动丢失。审计调用者主动填写的理由仍属于审计详情，不能填写凭证。保留策略不是合规认证。

V34 是新增列和索引；旧调试记录按创建时间开始适用保留策略。数据库恢复必须恢复该策略，并重新执行删除与撤权记录，详见运维文档。
