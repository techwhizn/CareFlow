# 企业基础设施增强验收矩阵

更新时间：2026-09-11。本文记录 CareFlow 企业内部底座增强的实现证据，范围限定为单机、低并发运行；不包含高性能指标、公开发布或 Release 操作。

| 能力 | 实现证据 | 数据/运维证据 | 自动化验证 | 状态 |
| --- | --- | --- | --- | --- |
| 安全审计与 CI 门禁 | `.github/workflows/ci.yml`、`.github/CODEOWNERS`、`.github/branch-protection.example.json` | [远端 CI 手册](../remote-ci.md)、只读核验脚本 [`verify-github-gates.py`](../../scripts/verify-github-gates.py) | Gitleaks 无泄漏；Trivy Maven/uv/npm 漏洞数均为 0；门禁脚本 4 项测试通过 | 本地完成；远端运行与保护规则需 GitHub API 凭据核验 |
| 统一身份认证与 MFA | `SsoService`、`MembershipService`、`MfaService`、`SsoController`、`MfaController` | Flyway V37/V40；OIDC issuer、introspection 和 MFA 配置见[配置手册](../configuration.md) | SSO、MFA、成员安全边界测试已覆盖；最近完整后端测试 189 项通过 | 已完成 |
| 备份、恢复与密钥轮换 | `scripts/cold-backup.py`、`scripts/cold-restore.py`、`scripts/rotate-backup-key.py`、`scripts/rotate-model-key.sh`、`ModelKeyRotationService` | [恢复手册](../recovery.md)、systemd backup service/timer、维护端点 | 备份加密/恢复/轮换与密钥重加密专项测试及恢复报告 | 已完成 |
| 数据保留、删除、脱敏与审计 | `RetentionService`、`ContentPurgeService`、审计记录和整租户边界 | [运维手册](../operations.md)、[可观测性](../observability.md)；查询正文和选项按策略脱敏 | 保留期、级联清理、审计与跨租户测试；终态集成投递按期限清理 | 已完成 |
| 知识质量反馈闭环 | `FeedbackService`、`ImprovementService`、`QualityFeedbackService`、`QualityFeedbackController` | [质量反馈手册](../quality.md)；反馈→改进任务→汇总流程 | 反馈权限、状态流转、租户汇总测试 | 已完成 |
| 企业系统集成接口 | `IntegrationService`、`IntegrationDeliveryService`、`IntegrationsController` | [集成手册](../integrations.md)；HTTPS、HMAC 签名、重试、投递审计与清理 | 签名、超时、重试、资源删除级联测试 | 已完成 |

## 当前验证边界

- Python Worker 与门禁脚本：Ruff 通过；`pytest worker/tests scripts/tests -q` 为 **159 passed, 1 skipped**。
- Java 最近一次完整验证为 **189 passed**；本轮环境没有 `mvn` 命令，未重复执行，后端代码未在该轮修改。
- Web 最近一次验证为 **15 passed**。
- 远端 Actions 成功状态和 `main` 分支保护属于 GitHub 外部状态。无 API Token 时脚本会明确失败，不把本地配置或旧运行结果记为远端通过。
