# V1-47 Java业务服务边界验证

日期：2026-09-10。

ManagementController和DocumentsController已移除。按初始化、应用配置、身份管理、用量、文档读取、草稿/索引、发布/删除与任务查询拆分Service及对应薄控制器。事务移至服务并保持原有边界；SQL归属各业务服务或既有Repository；下载使用SourceFile类型传递文件名和字节。

这是受回归保护的结构调整，没有新增产品行为或数据库迁移。Java Spotless/Maven verify 52项通过，保留租户/授权、已发布不可变、修订冲突、上传短事务、失败补偿及任务隔离用例。Java SDK 4项和Python SDK 16项通过。

在本机真实MySQL服务启动后，逐一比较调整前后OpenAPI：此前全部路径/HTTP方法仍存在。实际验证/me、应用、凭证元数据、用量、审计、任务、文档版本/切片和原文件下载；合成DOCX仍返回4个结构切片，下载返回ZIP签名和attachment响应头。OpenAPI及架构文档同步。

限制：Db底层仍为通用SQL工具；检索/问答历史和内部Worker协议仍有Map及控制器数据访问，后续在对应业务任务收敛。此项不宣称全部领域实体已强类型化，也不代表远端CI、生产隔离或完整RAG验收。未推送或对外发布。
