# CareFlow

CareFlow 是面向企业场景的开源知识库底座，采用“独立知识库底座 + 可扩展应用层”架构。系统负责文档生命周期、内容解析、检索、引用问答、权限隔离、应用接入和用量审计；业务意图识别、流程编排和 Agent 工具由上层应用配置与实现。

> 当前版本：`0.1.0` 开发增量。项目面向单机、低并发部署进行验证，优先保证功能正确、权限边界清晰和数据可追溯；不承诺高并发容量、高可用或生产 SLA。当前没有创建公开 Release、发布镜像或上传 SDK 包。

## 产品范围

CareFlow 的知识库底座包括：

- 企业、成员、角色、应用凭证和租户隔离；
- PDF、Office、Markdown、文本、表格及图片 OCR 导入；
- 文档版本、解析任务、可追溯切片、草稿修订和内容发布；
- Embedding、关键词 BM25、混合 RRF、Rerank 和类型化元数据过滤；
- 证据上下文、引用定位、流式问答、多轮会话、取消和断流状态；
- 用量、额度、成本核算、审计、任务恢复、备份和数据清理；
- Java 和 Python SDK，以及面向应用层的公共 API。

通用 Agent 执行平台、视频转写、高并发推理、GPU 调度和 Kubernetes 高可用部署不属于当前版本的承诺范围。

## 架构

```text
Web / Java SDK / Python SDK
            │  公共 Java API
            ▼
Java Backend ── MySQL（身份、授权、元数据、任务、发布、计量）
      │
      ├── RabbitMQ（异步任务）
      ├── Object Storage（原始文件）
      └── Python Worker
             ├── 解析、OCR、Token 切片
             ├── Embedding / Rerank / Generation 适配
             └── Milvus（向量与关键词索引）
```

Java 拥有身份、授权、元数据、任务、发布和计量；Python 承担解析、模型和检索适配；Web 只调用公共 Java API。模型输出不授予权限，也不直接执行敏感业务动作。详细边界见[架构说明](docs/architecture.md)和[访问边界](docs/access-boundaries.md)。

## 快速开始

### 环境要求

- Docker Desktop 与 Docker Compose；
- Java 21（主机开发和 SDK 验证）；
- Python 3.12、Node.js 22（构建和开发）；
- 可用的 Embedding、Rerank 和 Generation 服务。

### 启动服务

```bash
python3 scripts/init-local-env.py
# 编辑 .env，填写模型地址、模型名称和密钥；.env 不得提交到 Git。
docker compose up -d --build
```

打开 <http://localhost:5173>。首次进入时选择“初始化企业”，使用 `.env` 中的 `BOOTSTRAP_TOKEN` 创建企业。所有者凭证只显示一次，应通过密码管理器保存。

完整的导入、索引、发布、检索和问答流程见[快速开始](docs/quickstart.md)。配置变量、密钥和模型版本规则见[配置参考](docs/configuration.md)。

## 模型配置

模型配置由 Java 管理并按版本冻结。Embedding、Rerank 和 Generation 必须分别配置；服务不可用、响应格式错误、维度不匹配或流中断时，系统显式失败，不返回模拟结果。

DeepSeek Generation 配置示例：

```text
GENERATION_BASE_URL=https://api.deepseek.com
GENERATION_MODEL=deepseek-v4-flash
GENERATION_API_KEY=<个人密钥>
```

密钥只允许放在服务端环境或受控密钥管理系统中，不得写入前端、日志、截图、Issue 或提交。Embedding 与 Rerank 需要独立配置；本机可以按[可选 CPU 模型服务](tools/local-models/README.md)部署固定版本的 BGE 模型。

配置完成后可执行真实连接检测：

```bash
uv run --project worker python scripts/check-models.py --env-file .env
```

该命令使用合成输入验证三个模型服务，输出脱敏状态和计时，不输出密钥或生成正文，可能产生供应商费用。

## API 与 SDK

Java API 默认地址为 <http://localhost:8080>，Swagger UI 为 <http://localhost:8080/swagger-ui/index.html>，OpenAPI 文件见 [`docs/openapi.json`](docs/openapi.json)。REST 前缀为 `/api/v1`，使用 Bearer 凭证认证。

- `POST /retrieval/search`：执行授权后的关键词、语义或混合检索；
- `POST /answers`：执行带引用的流式问答；
- POST 请求支持 `Idempotency-Key`，避免重复提交和重复计量；
- 无权访问的对象统一返回 404，不泄露对象是否存在；
- 客户端传入的 `tenant_id` 和 `user_id` 不参与可信授权。

Java 与 Python SDK 覆盖知识库、文档、任务、索引、发布、检索和流式问答。SDK 使用方式和兼容范围见 [`sdk/README.md`](sdk/README.md)。内部 Java–Python 协议见[内部协议](docs/internal-contracts.md)。

## 开发与验证

```bash
mvn -B -f backend/pom.xml spotless:check verify
mvn -B -f sdk/java/pom.xml spotless:check verify
uv sync --project worker --frozen
uv run --project worker ruff check worker scripts sdk/python tools/local-models
uv run --project worker ruff format --check worker scripts sdk/python tools/local-models
uv run --project worker pytest worker/tests -q
npm ci --prefix web
npm test --prefix web
npm run build --prefix web
```

真实模型、Milvus、浏览器和恢复验证不由单元测试替代，复现命令和范围见[贡献指南](CONTRIBUTING.md)与[验收记录](docs/reports/acceptance.md)。

## 部署边界与已知限制

- 当前验证目标是 Linux/amd64 单机、低并发运行；
- 本地 CPU Rerank 适合开发和低并发使用，高并发请求可能返回模型忙碌或不可用错误；
- 数据库迁移按 Flyway 顺序执行，禁止修改已应用迁移；
- 删除先执行业务隔离，再由持久化清理任务处理文件、切片、缓存和索引；
- 恢复前必须核对备份、密钥、删除、撤权和成员状态，不确定时保持入口关闭；
- 当前版本仍有待完成的人工质量评审、安全审计及远端仓库保护核验。

部署、升级、恢复和故障处理见[部署说明](docs/production-deployment.md)、[运维手册](docs/operations.md)、[恢复手册](docs/recovery.md)和[故障排查](docs/troubleshooting.md)。

## 文档与参与方式

- [文档导航](docs/README.md)：按读者和主题索引全部文档；
- [扩展指南](docs/extension-guide.md)：解析器、模型适配器、检索策略和应用层扩展；
- [架构决策记录](docs/adr/)：重要设计取舍和兼容约束；
- [贡献指南](CONTRIBUTING.md)：开发、测试、提交和评审要求；
- [治理规则](GOVERNANCE.md)与[行为准则](CODE_OF_CONDUCT.md)：项目决策和社区协作；
- [安全政策](SECURITY.md)：漏洞私密报告渠道和处理范围；
- [变更记录](CHANGELOG.md)：版本变化与已知限制。

提交贡献前请先阅读 [AGENTS.md](AGENTS.md)、[质量标准](docs/quality.md)和[任务清单](docs/roadmap/v1.0-tasks.md)。

## 许可证

CareFlow 采用 [Apache License 2.0](LICENSE)。第三方依赖和分发声明见 [NOTICE](NOTICE)、[THIRD_PARTY.md](THIRD_PARTY.md)及 [`docs/licenses/`](docs/licenses/)。
