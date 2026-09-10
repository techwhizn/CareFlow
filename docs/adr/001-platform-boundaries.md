# ADR 001：独立知识底座与模块化单体

状态：接受，2026-09-10。

仓库检查：无业务源码，只有未跟踪的旧环境示例、忽略规则及失效 CI。保留现有文件并按新需求调整，无迁移存量数据库。

Java 21 / Spring Boot 3.5.5 主后端拥有身份、ACL、元数据、版本、任务、发布与计量。Python 3.12 Worker 提供受内部密钥保护的解析、模型与 Milvus 适配器。React 19 / TypeScript / Vite 7.3.6 管理后台只调用 Java API。MySQL 8.4 是权威数据库；RabbitMQ 投递 Outbox；S3 保存文件；Redis 预留限流基础设施。

每份发布版本不可变，发布事务只切换 MySQL 指针。Milvus 同时保存草稿和旧版本，请求用服务端确定的 tenant_id + version_id 白名单过滤两路召回，并在送模型前和返回前复核权限。Collection 名由租户及模型配置指纹生成，避免同维度模型混用。索引 Strong consistency 查回完整记录后才标记 READY。

权限交集：活动成员 + 企业 + 知识库 ACL + 文档 ACL + 应用绑定 + 发布范围。所有者也受文档 ACL 收窄约束。API Key 作为独立应用主体，不信任客户端 user_id。Worker 只接受主后端签发的内部任务，不对外暴露端口。系统异常拒绝访问。

上传以数据库任务 + Outbox 同一事务落库。Worker 用租约和 fencing token 领取任务；回调必须匹配租约且对象未删除。重投不重复有效数据。发布/撤权/删除锁定企业行，检索在每个敏感边界按当前 ACL 复核。已传输内容无法追回。

MVP 先实现单文件/单版本纵向闭环，未完成的 P0 必须留在需求表中。不得以本地组件测试代替真实模型/完整基础设施验收。

参考：
- [Spring Boot 3.5 requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html)
- [Milvus 2.6 full text](https://milvus.io/docs/v2.6.x/full-text-search.md)
- [Hybrid search](https://milvus.io/docs/v2.6.x/multi-vector-search.md)
- [Chinese analyzer](https://milvus.io/docs/chinese-analyzer.md)

Java 21 为部署基线；精确依赖版本及锁文件由构建验证，生产升级须另行兼容测试。

补充验证：实际采用 Java 25.0.2 编译目标 Java 21；MySQL 8.4.6 / Milvus 2.6.1 已启动并进行组件验证。Flyway 对 MySQL 的支持版本提示保留为生产升级核验事项。
