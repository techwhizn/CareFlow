# 验收记录 · 2026-09-10

结论：交付了可运行的开发增量，**未完成全部 P0，不能按商业级生产系统验收**。

## 已执行

| 检查 | 结果 | 范围 |
|---|---|---|
| Java Maven package/test | 17 项通过 | H2 + 明确测试替身；租户隔离、发布不可变、草稿隔离、ACL 收窄隐藏历史答案、迟到回调、幂等上传、额度并发、凭证撤销、应用版本回滚授权 |
| Python pytest | 18 项通过、1 项默认跳过 | 真正解析 MD/TXT/CSV/DOCX/XLSX、Token 边界、损坏/加密文件拒绝、模型缺配置错误、RRF、确定性指标；跳过的是需显式启用的 Milvus 用例 |
| 真实 Milvus 集成用例 | 1 项通过 | Milvus 2.6.1 中文 BM25；其他租户和旧版本的相同文本不被召回。测试后清理专用 Collection |
| 前端 TypeScript / Vite 构建 | 通过 | React 19 / Vite 7.3.6；页面已拆分模块 |
| npm audit | 0 个已知漏洞 | 开发和运行依赖，实际审计结果；不代替全面安全审计 |
| MySQL 8.4.6 迁移 | 3 个迁移成功 | 实际数据库 v3；Flyway 仍有 MySQL 版本支持提示，需生产前升级兼容核验 |
| 真实导入链路 | 通过 | S3 保存、MySQL 状态、RabbitMQ 投递、Worker 解析、租约回调、页面切片；详见 JSON 报告 |
| 浏览器检查 | 已执行 | 登录、工作台、知识库导航、文档详情、实际来源文本和切片，未索引的“发布”按钮禁用；当前窄屏布局已检查 |
| Docker Compose 基础设施 | 运行 | MySQL、RabbitMQ、MinIO、etcd、Milvus、Redis；Milvus/MySQL/RabbitMQ 健康检查通过 |

## 尚未通过

- Embedding、Rerank、生成模型的真实完整链路：当前 `.env` 未配置模型地址、名称和凭证，不能完成 AC-20。
- 全量容器构建与 OCR 运行验证：两次构建均因 `auth.docker.io` 网络超时无法拉取 Python 基础镜像。已运行基础设施和本机 Python 不受此项阻断。
- 性能基准（10万切片/10租户）、120题质量评估、拒答/引用准确率、备份恢复演练：未执行，不填写虚构指标。
- 部分 P0 功能仍未实现，不能把全部未完成项归咎于缺少模型凭证。逐项见 `requirements.md`。

## 证据文件与复现

- `import-integration.json`：第一轮真实上传，10个基础切片。
- `structured-import-integration.json`：结构切片更新后的实际导入结果。
- `model-configuration-blocker.json`：真实索引任务在缺模型时失败，仅尝试一次，未使用模拟结果。
- `browser-edit-integration.json`：浏览器实际修订成功，Token 从56更新为74，修订号变为1，原始文本与S3文件保持不变。
- `backend/target/surefire-reports/`：可重新运行的 Java 结果（构建目录，不提交）。
- `worker/tests/test_milvus_integration.py`：`RUN_MILVUS_INTEGRATION=1 uv run --project worker pytest worker/tests/test_milvus_integration.py -q`。
- `scripts/real-smoke.py`：模型配置齐全后执行上传、索引、发布、双路召回、重排和 SSE 验证。

本机为 macOS x86_64，构建及主后端使用 JBR Java 25.0.2、目标 Java 21，Worker Python 3.12.13。容器为单机开发部署；没有生产 HA 或 SLA 声明。未提交真实凭证、模型密钥或用户业务资料。
