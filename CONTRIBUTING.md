# 贡献指南

CareFlow 当前是 0.1 开发增量。开始贡献前阅读 [README](README.md)、[架构](docs/architecture.md)、[质量标准](docs/quality.md) 与 [需求状态](docs/reports/requirements.md)。项目所有者已选择 [Apache-2.0](LICENSE)。贡献按该许可证提供；添加许可证不代表1.0功能或发布验收完成。

## 开发与变更

按 README 安装 Java 21、Python 3.12、uv、Node.js 22 和 Docker Compose。Python 与前端依赖使用锁文件安装。`.env` 由初始化脚本在本机生成，不提交凭证。测试使用合成数据。

从默认分支创建短生命周期分支。PR 描述问题、修改后的行为和验证结果；架构或接口变更先补 ADR 或接口说明。避免把重构、格式化和无关功能放进同一个功能 PR。数据库结构变化新增 Flyway 迁移，并说明旧数据如何处理。

## 本地检查

在仓库根目录执行，与 CI 一致：

```bash
mvn -B -f backend/pom.xml spotless:check verify
uv sync --project worker --frozen
uv run --project worker ruff check worker scripts
uv run --project worker ruff format --check worker scripts
uv run --project worker pytest worker/tests -q
npm ci --prefix web
npm run build --prefix web
npm audit --prefix web --omit=dev --audit-level=high
```

修复格式可运行 `mvn -f backend/pom.xml spotless:apply` 与 `uv run --project worker ruff format worker scripts`。Python 静态检查会检查未使用导入、未定义名称和导入顺序；TypeScript 开启严格模式、未使用代码及 switch 穿透检查。

单元测试不依赖付费模型。真实服务验证单独运行 README 中的 smoke 脚本：它会调用模型并创建测试数据，需自行准备凭证与服务。记录模型、数据集、时间和限制，脱敏后提交报告；不得提交原始业务正文或令牌。

SDK 变更还需执行 [客户端验证命令](sdk/README.md#验证)，同步维护两种语言的协议行为。

## 评审与发布

评审人应确认权限与租户边界、错误处理、事务一致性、输入验证及测试的行为覆盖。文档示例必须与实际接口一致。公共 PR 不处理凭证或漏洞利用细节，参见 [安全政策](SECURITY.md)。

合并前所有适用 CI 检查通过。发布前按 [质量标准](docs/quality.md) 核验许可证、依赖、真实集成、升级与恢复证据。GitHub 分支保护需要仓库管理员在远端开启；本地 CI 文件不等于已经启用远端保护。
