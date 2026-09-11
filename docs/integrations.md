# 企业系统集成

CareFlow 通过租户级 Webhook 集成向企业门户、工单系统或审计平台传递事件。集成配置由租户 OWNER、ADMIN 或 KNOWLEDGE_MANAGER 管理，端点密钥使用平台密钥库加密保存，列表接口不会返回明文密钥。

## 配置接口

- `GET /api/v1/integrations`：列出当前租户集成及订阅事件。
- `POST /api/v1/integrations`：创建 Webhook。请求包含 `name`、`kind=webhook`、`endpoint_url`、至少16字符的 `secret` 和事件集合 `events`。
- `DELETE /api/v1/integrations/{id}?revision=N`：按乐观锁版本停用集成。

生产端点必须使用 HTTPS；仅允许 `localhost` 或 `127.0.0.1` 使用 HTTP 进行本地联调。每次创建和停用均写入租户审计日志。

当前版本完成了安全的端点配置、密钥保护和租户边界。事件投递队列、重试策略和下游回执应在接入具体企业系统前启用；未配置投递器时，平台不会伪造“已送达”状态。
