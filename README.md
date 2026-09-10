# CareFlow 企业知识平台

依据 [PRD.md](PRD.md) 建设的独立知识库底座与应用层。当前为 **0.1 开发增量**，不是已完成全部 P0 的商业交付。真实服务未配置时明确失败，不返回模拟向量或模拟重排。

## 目录

- `backend/`：Java 21、Spring Boot，身份/权限、文件版本、任务 Outbox、发布、应用、问答交付和查询额度。
- `worker/`：Python 3.12，文件解析、OCR、Token 切片、真实模型适配、Milvus dense + BM25、RRF、重排和生成。
- `web/`：React + TypeScript 中文后台，连接实际 API。
- `docs/`：M0、架构决策、运维说明、验收记录与未完成项。

## 本地启动

需要 Docker Compose、足够运行 Milvus 的内存和磁盘。默认只绑定本机端口。

```bash
python3 scripts/init-local-env.py
# 编辑 .env：填写三个真实模型的地址、模型名称与密钥；不要提交真实凭证。
docker compose up -d --build
```

打开 http://localhost:5173 。首次选择“初始化企业”，使用 `.env` 中的 `BOOTSTRAP_TOKEN` 创建企业。返回的所有者凭证仅显示一次，请妥善保存。后续使用个人凭证登录。应用 API Key 不具备管理后台权限。部署管理员开通额外企业时，在初始化模式勾选“开通另一企业”；每家企业使用独立身份与数据范围。

Embedding 需配置 `EMBEDDING_REVISION` 和真实维度；模型输出维度不匹配时任务失败，不裁切或伪造向量。Rerank 使用 `/rerank` 的 `results[].index/relevance_score` 契约。生成模型必须支持 `/chat/completions` SSE。使用自托管模型时 key 可空，地址和模型名仍为必填。

DeepSeek 生成服务：`GENERATION_BASE_URL=https://api.deepseek.com`，`GENERATION_MODEL=deepseek-v4-flash`，在 `.env` 的 `GENERATION_API_KEY` 填写个人密钥，然后重启 Worker API。密钥仅保存在本地配置，不写入前端。Embedding 与 Rerank 仍需分别配置。

### 在主机开发

```bash
docker compose up -d mysql rabbitmq redis minio etcd milvus
mvn -f backend/pom.xml package
uv sync --project worker --frozen
npm ci --prefix web
# 在四个独立终端分别运行：
python3 scripts/run-local.py backend
python3 scripts/run-local.py worker-api
python3 scripts/run-local.py worker-consumer
python3 scripts/run-local.py web
```

Java 21 为目标运行环境。可以用受 Spring Boot 支持的 Java 25 编译 `--release 21`；当前机器的 Java 26 不属于已验证范围，请通过 `JAVA_HOME` 指定兼容 JDK。

## 第一次验证

1. 创建知识库，上传 `examples/product-guide.md`。
2. 在任务中心等待解析完成，文档详情出现切片后核对来源。
3. 点击“建立索引”，真实 Embedding 与 Milvus 校验完成后显示“处理就绪”。
4. 点击“发布此版本”。上传/解析/索引完成都不会自动发布。
5. 在检索调试台搜索 `CF-100 报 E404 怎么处理`，核对 dense/BM25/RRF/模型 Rerank。
6. 在问答页面查看逐段流式输出与证据。切片修订先复制草稿，旧发布仍可检索。

真实验收脚本（凭证从环境读取，不作为命令参数）：

```bash
# 将 CAREFLOW_TOKEN 通过本机安全方式放入进程环境
uv run --project worker python scripts/real-smoke.py
```

该脚本创建一个明确标记的合成测试知识库，并写入真实步骤报告。缺少模型或服务会退出失败。

## 多语言客户端

提供 [Java 与 Python SDK](sdk/README.md)，覆盖知识库创建、上传、任务查询、索引、发布、搜索和流式问答。Python 支持同步与异步调用；所有权限由公共 API 校验。

## API

主服务提供 [Swagger UI](http://localhost:8080/swagger-ui/index.html) 和 [OpenAPI](http://localhost:8080/v3/api-docs)。REST 前缀 `/api/v1`，Bearer 凭证确定企业与身份。客户端 tenant_id/user_id 不参与可信认证。内部 API 用不同的服务密钥及任务租约保护。

- 搜索：`POST /retrieval/search`，含 `query`、`mode`、`limit`、`knowledge_base_ids`，可选 `application_id`。
- 问答：`POST /answers`，同上，事件 `start/status/delta/citations/done/error`。
- POST 调用携带 `Idempotency-Key`；问答/检索重复键返回 409，避免重复扣费，当前不重放旧响应。
- 不可访问对象统一 404，不区分不存在与无权限。

## 验证命令

```bash
mvn -f backend/pom.xml spotless:check verify
uv run --project worker ruff check worker scripts
uv run --project worker ruff format --check worker scripts
uv run --project worker pytest worker/tests -q
npm run build --prefix web
npm audit --prefix web
```

单元/组件测试使用 H2 和明确的测试替身，不作为 Milvus、真实模型、MySQL 全链路或生产隔离的验收证据。详情见 [验收报告](docs/reports/acceptance.md) 和 [需求状态](docs/reports/requirements.md)。恢复、数据清理与生产限制见 [运维说明](docs/operations.md)。

## 1.0 开源质量完善计划

[剩余任务清单](docs/roadmap/v1.0-tasks.md)列出53个工作包、前置依赖与验收条件，覆盖原PRD全部P0及SDK、开源交付要求；当前仍是开发增量。

## 参与开发

请先阅读 [贡献指南](CONTRIBUTING.md)、[质量基线与架构改进计划](docs/quality.md) 和 [安全政策](SECURITY.md)。当前许可证待确定，尚未完成开源发布；公开发布前需补齐许可证与发布检查。

套餐人工开通、限制单位、有效期和迁移规则见 [套餐与资源限制](docs/entitlements.md)。
