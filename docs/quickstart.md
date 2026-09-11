# 快速开始

本指南在本机用 Docker Compose 启动 CareFlow，创建一个企业，导入示例资料，并完成检索和问答。它适合开发和低并发试用，不代表生产容量或高可用部署。

## 前置条件

- Docker Desktop 与 Compose；
- Java 21（仅主机开发需要）、Python 3.12、Node.js 22（构建 SDK/Web 时需要）；
- 可用的 Embedding、Rerank 和生成模型服务。生成模型可使用 DeepSeek；Embedding/Rerank 需要单独配置。

## 启动

```bash
python3 scripts/init-local-env.py
# 编辑 .env，填写模型地址、名称和密钥；不要把 .env 提交到 Git。
docker compose up -d --build
```

打开 <http://localhost:5173>。第一次选择“初始化企业”，使用 `.env` 中的 `BOOTSTRAP_TOKEN`；项目没有固定默认密钥。可在 macOS 通过 `sed -n 's/^BOOTSTRAP_TOKEN=//p' .env | pbcopy` 安全复制，修改 `.env` 后需要 `docker compose up -d --force-recreate backend`。所有者凭证只显示一次，请通过密码管理器保存。

如果企业已经初始化过，不要再次使用初始化入口；请改用所有者或管理员访问凭证登录。初始化接口只允许创建首个企业一次。

## 完成第一条检索

1. 创建知识库并上传 `examples/product-guide.md`。
2. 在任务中心等待解析完成，打开文档详情核对切片来源。
3. 点击“建立索引”，等待真实 Embedding 和 Milvus 校验完成。
4. 点击“发布此版本”；解析或索引完成不会自动发布。
5. 在检索调试台查询 `CF-100 报 E404 怎么处理`，检查命中证据和来源。
6. 在问答页查看流式回答、引用和用量。

## 模型配置

DeepSeek 生成配置示例：

```text
GENERATION_BASE_URL=https://api.deepseek.com
GENERATION_MODEL=deepseek-v4-flash
GENERATION_API_KEY=<个人密钥>
```

密钥只放在服务端环境变量或受控密钥管理系统中，不写入前端、Issue、日志或提交。缺少模型配置时系统应显式失败，不返回模拟向量、模拟重排或伪造答案。

## API 与 SDK

公共 API 的 Swagger UI 位于 <http://localhost:8080/swagger-ui/index.html>。REST 前缀为 `/api/v1`，客户端使用 Bearer 凭证。Java 和 Python SDK 示例见 [SDK 文档](../sdk/README.md)。

## 停止与清理

```bash
docker compose down
```

要删除本地卷并重新开始，请先确认不需要本地数据，再运行 `docker compose down --volumes`。生产数据不得使用此命令代替 [恢复手册](recovery.md) 中的备份和删除流程。
