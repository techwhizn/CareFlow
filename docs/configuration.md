# 配置参考

配置通过环境变量和 Java 管理页面提供。`.env` 只用于本地部署，生产环境应使用密钥管理系统或编排平台 Secret。所有变量名以 `.env.example` 和 Compose 文件为准；未知变量不会自动授予权限。

## 基础服务

| 配置 | 用途 |
| --- | --- |
| `DATABASE_URL` | Java 连接 MySQL |
| `RABBITMQ_HOST` | 任务消息队列 |
| `S3_ENDPOINT`、`S3_ACCESS_KEY`、`S3_SECRET_KEY` | 原始文件对象存储 |
| `MILVUS_URI`、`MILVUS_TOKEN` | Python 向量/关键词索引 |
| `INTERNAL_TOKEN` | Java 与 Worker 内部身份 |
| `MODEL_CONFIG_ENCRYPTION_KEY` | 模型凭证加密主密钥，必须是 Base64 编码的32字节值 |
| `MODEL_CONFIG_ENCRYPTION_KEY_OLD` | 仅主密钥轮换维护窗口临时使用的旧密钥，成功后必须移除 |
| `SSO_INTROSPECTION_URL` | OIDC OAuth 2.0 introspection HTTPS 地址 |
| `SSO_CLIENT_ID`、`SSO_CLIENT_SECRET` | 调用 IdP introspection 的客户端凭证 |

## 模型

Java 管理页面保存模型档案和配置版本；Worker 根据请求中的冻结配置调用模型。Embedding、Rerank、Generation 必须分别指定地址、模型名和能力。DeepSeek 示例：

```text
GENERATION_BASE_URL=https://api.deepseek.com
GENERATION_MODEL=deepseek-v4-flash
GENERATION_API_KEY=<个人密钥>
```

自托管模型可以使用空 key，但地址、模型名称、响应协议和网络白名单仍必须满足校验。模型连接失败时系统显式返回安全错误，不回显供应商正文或密钥。

## 企业统一身份认证

配置上述 SSO 变量后，调用 `POST /api/v1/sso/exchange` 提交外部 Bearer Token。IdP 必须返回 `active=true`、`sub`、`iss` 和 `tenant_id`；CareFlow 仅接受已预先绑定外部主体的启用成员，成功后签发8小时成员凭证，不自动创建成员或提升角色。后续请求仍执行租户授权和成员 MFA。

## 变更规则

模型、切片、检索和回答配置发布后形成不可变版本；执行中的任务使用启动时快照。修改配置不会重写历史索引、账单或问答记录。配置变更应通过管理 API，并由审计记录操作者、前后版本和原因。

## 测试模式快速登录

本地测试可在 `.env` 显式设置 `DEMO_AUTH_ENABLED=true`，登录页会显示“测试模式快速进入”，为已初始化企业的所有者签发 8 小时测试凭证。该开关默认 `false`，生产环境必须保持关闭；测试凭证仍受资源权限和已启用 MFA 规则约束。修改后执行 `docker compose up -d --force-recreate backend`。

## 安全检查

不要把密钥放在前端、源码、命令历史、截图或 Issue 中。启动后通过健康检查、模型连接检测和一条合成资料验证配置；缺少关键配置应修复依赖后重试，不使用模拟服务掩盖失败。
