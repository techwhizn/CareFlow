# CareFlow 文档导航

CareFlow 是“独立知识库底座 + 可扩展应用层”。当前版本优先保证单机、低并发环境下的知识库功能；通用 Agent 执行平台和高并发部署属于后续范围。仓库目前仅用于代码展示和内部维护，暂不接受外部 Pull Request 或代码贡献。

## 按读者进入

- 新用户：先看 [快速开始](quickstart.md)，再按[人工验收流程](manual-test-flow.md)完成上传到查询，随后阅读[用户操作手册](user-guide.md)、[配置参考](configuration.md) 和 [故障排查](troubleshooting.md)。用户手册包含登录、角色、页面导航、上传发布、问答、反馈和企业设置的完整操作路径。
- 部署者：看 [生产部署](production-deployment.md)、[运维手册](operations.md)、[恢复手册](recovery.md) 和 [安全政策](../SECURITY.md)。
- 集成开发者：看 [API](api.md)、[Java/Python SDK](../sdk/README.md)、[内部协议](internal-contracts.md) 和 [扩展指南](extension-guide.md)。
- 维护者/内部开发者：看 [质量标准](quality.md)、[架构](architecture.md)、[ADR](adr/) 和 [贡献指南](../CONTRIBUTING.md)。
- 维护者：看 [治理](../GOVERNANCE.md)、[发布流程](release-process.md)、[验收记录](reports/acceptance.md) 和 [任务清单](roadmap/v1.0-tasks.md)。
- 投资人与合作伙伴：看[投资人说明书](investor-brief.md)和[企业增强验收矩阵](reports/enterprise-enhancements.md)。

## 按主题进入

| 主题 | 文档 |
| --- | --- |
| 架构与边界 | [架构](architecture.md)、[访问边界](access-boundaries.md)、[配置版本](knowledge-configurations.md) |
| 知识库 | [解析](parsing.md)、[切片](chunking.md)、[编辑](chunk-editing.md)、[发布](document-publications.md)、[增量索引](incremental-indexing.md) |
| 检索与问答 | [元数据过滤](metadata-filters.md)、[上下文](evidence-context.md)、[会话](conversations.md)、[流式回答](answer-streaming.md)、[引用](citations.md) |
| 平台运营 | [配额](entitlements.md)、[计量](model-usage.md)、[账单](billing.md)、[可观测性](observability.md)、[清理](operations.md)、[企业集成](integrations.md) |
| 日常操作 | [用户操作手册](user-guide.md)、[快速开始](quickstart.md)、[人工验收流程](manual-test-flow.md)、[故障排查](troubleshooting.md) |

用户界面以当前实现为准：上传文档后默认自动推进解析、索引和发布；文档详情仍保留手动操作，用于自动流程因配置、冲突或失败而暂停时恢复处理。专项验收报告中的历史状态不代表当前页面行为。
| 质量与证据 | [质量标准](quality.md)、[评估](evaluation.md)、[企业增强验收矩阵](reports/enterprise-enhancements.md)、[验收报告](reports/acceptance.md)、[已知限制](local-artifacts.md) |

## 文档约定

文档中的“已验证”必须指向可复现命令或报告；历史实验保留原始结果，不改写成当前通过。示例使用合成数据，不包含凭证或业务正文。接口和配置发生变化时，同一提交必须同步更新文档、OpenAPI 和迁移说明。
