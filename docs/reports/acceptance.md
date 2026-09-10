# 验收记录 · 2026-09-11

结论：交付了可运行的开发增量，**未完成全部 P0，不能按商业级生产系统验收**。

## 最新专项验证

任务清单已关闭48/53项，整体仍未达到1.0完成条件。以下为当前进展；后文明确标注的历史数量不作为当前结果。

- Java180项行为测试通过；Worker155项通过、1项默认跳过，另显式运行真实Milvus测试1项通过；本地模型12项、Web15项与Chromium组件回归通过，Ruff、TypeScript和构建通过。Java SDK23项、Python SDK在3.10/3.12/3.14各37项通过，见[V1-35](v1-35-sdk.md)。
- [20项功能验收](v1-42-acceptance-matrix.md)均已有范围明确的证据，人工质量与安全门禁仍未全部通过。
- MySQL当前V36；独立空卷安装、浏览器导入至引用问答、12页面窄屏与键盘回归通过，见[V1-49](v1-49-browser-compatibility.md)。
- 最新删除/撤权重放、原文件SHA256、索引重建及V35→V36迁移通过。补齐RabbitMQ节点身份后的冷恢复RTO796.31秒（含操作暂停）、停写窗口RPO0；历史114秒未核对队列，范围与限制见[V1-46](v1-46-recovery.md)。
- 120题检索对照与答案实验已执行；答案115次完成、5次失败，原始失败保留。参考答案人工核对与生成答案人工评分均为0，不能宣称质量达标，见[答案报告](v1-41-answer-pilot.md)。
- 十万有效分块/十租户性能实验及批量40复测已完成：复测普通API100次全成功，P95约28ms；混合检索97%失败，首轮首Token95%失败，未达建议目标，见[性能报告](v1-45-performance.md)。所有者接受2核CPU重排容量为已知限制，不再把性能数字作为1.0功能门禁，也不宣称高并发SLA。
- 应用依赖与镜像已做专项修复；基础设施和部分OS告警仍在审查。远端CI/分支保护尚未验证，已准备[配置示例与启用手册](../remote-ci.md)。代码已推送至GitHub `main`，未创建Release或发布公开包/镜像。

## 中间阶段历史记录（17/53时）

- Java77项通过；Worker60项通过、1项环境用例显式跳过；可选模型服务5项、Java SDK6项、Python SDK18项通过；前端构建与Ruff通过。
- MySQL升级到V14；真实BGE Embedding/Rerank、Milvus双路召回与DeepSeek完整SSE链路通过，见 [V1-02](v1-02-models.md)。
- Linux容器构建、中英文OCR、页数/像素/解压/超时/内存/CPU限制及上传到人工修订链路通过，见 [V1-13](v1-13-ocr.md)。
- 真实索引发布切换与回滚、过滤命中/排除、双租户队列公平性通过，见 [发布](v1-15-publication-isolation.md)、[过滤](v1-25-metadata-filters.md)、[调度](v1-44-fair-dispatch.md)。
- 旧索引身份保护及请求级模型上下文已验证；V1-03配置发布与任务快照仍开发中，见 [进展](v1-03-model-context.md)。
- 性能、120题人工评审、恢复演练及其他功能和工程验收仍未完成。提交均在本地，未推送或发布。

## 首轮历史记录（不代表当前阻塞）

下列测试数量及模型/OCR构建阻塞保留为首轮事实；后续进展以上述专项报告为准。

### 首轮已执行

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

### 首轮尚未通过（模型与OCR现已补齐）

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
