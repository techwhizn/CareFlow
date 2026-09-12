# 快速开始

本指南在本机用 Docker Compose 启动 CareFlow，创建一个企业，导入示例资料，并完成检索和问答。它适合开发和低并发试用，不代表生产容量或高可用部署。

## 前置条件

- Docker Desktop 与 Compose；
- Java 21（仅主机开发需要）、Python 3.12、Node.js 22（构建 SDK/Web 时需要）；
- 可用的 Embedding、Rerank 和生成模型服务。生成模型可使用 DeepSeek；Embedding/Rerank 需要单独配置。

## 启动

推荐使用一键安装脚本：

```bash
./scripts/install.sh
```

脚本会保留已有 `.env`，首次运行时生成本地密钥，构建 `backend`、`worker`、`web` 镜像，并启动 MySQL、RabbitMQ、MinIO、etcd、Milvus 及两个 Worker 容器。Docker 镜像下载需要网络；安装完成后可用 `docker compose ps` 查看健康状态。

如果需要分步执行，也可以使用下面的命令：

```bash
python3 scripts/init-local-env.py
# 编辑 .env，填写模型地址、名称和密钥；不要把 .env 提交到 Git。
docker compose up -d --build
```

`tools/mysql-recovery` 和 `tools/local-models` 是可选工具，不属于默认安装；DeepSeek 通过 API 调用，不需要额外 Docker 镜像。

打开 <http://localhost:5173>。第一次选择“初始化企业”，使用 `.env` 中的 `BOOTSTRAP_TOKEN`；创建成功后页面显示一次性所有者访问凭证，点击“进入工作空间”完成登录。生成环境时可直接运行 `python3 scripts/init-local-env.py --copy-bootstrap-token`，在 macOS、Wayland Linux 或安装了 xclip 的 Linux 上自动复制初始化密钥到剪贴板；已生成过的环境运行 `python3 scripts/copy-bootstrap-token.py` 即可复制，不会在终端显示。`DEFAULT_ACCESS_TOKEN` 仅用于部署管理员通过“开通另一企业”创建额外租户，不用于首个企业初始化。修改 `.env` 后需要 `docker compose up -d --force-recreate backend`。访问凭证只保存在部署机 `.env` 或浏览器会话中，请通过密码管理器保存并按需轮换。

如果企业已经初始化过，不要再次使用初始化入口；请改用所有者或管理员访问凭证登录。初始化接口只允许创建首个企业一次。

## 完成第一条检索

1. 创建知识库并上传 `examples/product-guide.md`。
2. 在任务中心等待解析完成，打开文档详情核对切片来源。
3. 上传成功后系统默认自动完成解析、索引和发布；在文档详情或任务中心等待真实 Embedding 和 Milvus 校验完成。
4. 如果自动流程因未配置处理模型、内容冲突或任务失败而停止，修正问题后可在文档流程卡中手动执行“建立索引”和“发布此版本”。
5. 在检索调试台查询 `CF-100 报 E404 怎么处理`，检查命中证据和来源。
6. 在“知识库问答”页查看流式回答、引用、历史记录和用量；如需评价答案，可在回答下方提交反馈。

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
