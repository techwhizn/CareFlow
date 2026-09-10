# M0 设计基线

模块依赖：Web → Java（Identity / Knowledge / Publication / Applications / Retrieval / Metering）→ 内部 Worker（Parsing / Chunking / Models / Milvus）。Worker 无业务意图识别逻辑。数据库迁移是核心对象的可执行定义；`/v3/api-docs` 为运行时 OpenAPI。

## 版本与数据

Tenant、Membership、Credential、KnowledgeBase、ResourcePermission、Document、DocumentVersion、Chunk、Job、Outbox、Publication、Application、ApplicationBinding、UsageEvent、Answer、AnswerEvidence、Audit。UUID 标识；所有查询约束租户，集合逐项授权后再返回。revision 用于冲突检查，旧发布不会被草稿改写。

## 接口

REST `/api/v1`。Bearer credential；租户及主体从密钥摘要查出。创建资源返回 ID，上传返回 document/version/job ID。错误包含 code、message、request_id。不可见与不存在统一 404。所有应用绑定在服务端复核。Worker `/internal/v1` 使用单独内部服务密钥，不接受公共身份。

## 顺序任务

1. M1 AUTH-01..08、KB-01..08、DOC-01..10：身份/ACL、知识库、文件和异步任务。
2. M2 CH-01..12、IDX-01..08：可追溯切片、草稿、真实向量、索引可见性确认、发布回滚。
3. M3 RET-01..12、QA-01..08：双路召回、RRF、外部模型重排、引用和调试。
4. M4 APP-01..06、BILL-01..10、NFR-05..06：应用凭证、原子额度、用量、审计。
5. M5 EVAL-01..08、NFR-01..10、AC-01..20：测试/恢复/压测及证据记录。

完成状态以 `docs/reports/requirements.md` 为准，不以存在页面或接口推断全部验收通过。

## 意图接口（仅契约）

`IntentRequestV1 {tenant_id, application_id, configuration_version, text, bounded_context, trace_id}` → `{candidates:[{name, confidence, entities, missing_parameters}], clarification_required, configuration_version, trace_id}`。支持多个候选；超时和供应商错误显式返回；输出是建议，不授权业务操作。P0 无实现、无页面。

## Java业务服务索引

- 初始化/身份：BootstrapService、EnterpriseProvisioning、MembershipService、IdentityAdministrationService、ApplicationCredentialService、AuthorizationService。
- 知识库：KnowledgeBaseService/Repository、KnowledgeLifecycleService。
- 文档：DocumentUploadService与UploadStaging、DocumentReadService、DocumentMetadataService/Repository、DocumentDraftService、DocumentPublicationService。
- 应用：ApplicationService。任务：Tasks、JobReadService。用量管理：UsageAdministrationService；检索计量仍由RetrievalService拥有。
- 公共控制器映射HTTP，Java服务拥有授权、事务及规则。新需求沿所属服务扩展，不重建通用管理控制器。
