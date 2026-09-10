# 基础设施安全升级候选验证

2026-09-11：[机器记录](v1-50-infrastructure-candidates.json)。这些镜像尚未替换默认Compose依赖，不代表V1-50完成。

- MySQL：固定摘要的官方8.4.11镜像移除未使用的mysql-shell及其内嵌Python，保留mysqld/mysql和原入口。复制已停止的8.4.6卷，在无网络隔离容器升级，36个迁移与保留文档ACTIVE状态一致。首次核查脚本误查询documents.state，改用实际status列后验证通过；不是数据库升级失败。`deploy/mysql/Dockerfile`可复现此候选，binlog恢复仍需匹配版本工具，不能混用当前仅验证8.4.6的恢复镜像。
- 剩余MySQL高/严重扫描项都属于gosu的Go标准库。对镜像内实际二进制执行govulncheck v1.8.0，48条模块/包级发现、0条函数符号发现；[gosu安全政策](https://github.com/tianon/gosu/security)说明应按实际调用检查。这一结论只适用于该二进制，不用于豁免etcd/Milvus。
- RabbitMQ：官方4.3.5候选无高/严重项。遵循[官方升级顺序](https://www.rabbitmq.com/docs/upgrade)，在独立旧卷副本执行4.1.4→4.2.9→4.3.5，每步启用稳定feature flags，保持原节点名，并读回同一条合成持久消息。原数据卷未升级；不能跳过中间版本或对升级卷直接换回旧镜像。
- S3：PGSTY Silo候选在旧MinIO卷副本启动，Java21/Minio8.6客户端读取原文件SHA256一致，8MiB分片上传/读取一致，删除后404、匿名访问403。它是独立维护分支，仍需Milvus兼容、供应链与告警审查后才能采用；不能把Java客户端测试当所有存储场景验收。

etcd3.5.33的函数级检查仍发现风险，未直接豁免。Worker OS、Milvus、可选模型镜像和存储候选的余项继续审查。报告保留扫描工具、摘要和范围；没有公开镜像或部署到外部环境。

后续已核对[Milvus 2.6.23发布说明](https://github.com/milvus-io/milvus/releases/tag/v2.6.23)，实际拉取并扫描镜像：仍有2严重、80高危，不能认定版本更新已经关闭全部问题。对2.6.22和2.6.23各自实际二进制执行govulncheck，均有1744条函数符号发现、涉及32个不同公告；符号存在不等于已证实外部可利用，但也不能沿用gosu“无受影响函数”的结论。

新的独立存储栈使用Silo、etcd3.5.33、Milvus2.6.23；真实中文BM25测试在写入、flush、释放集合并重新加载后仍只返回指定租户/版本，原默认栈同一测试也通过。测试后移除唯一合成集合。[镜像摘要及结构化记录](v1-50-storage-compatibility.json)。这补齐新存储路径的持久化兼容检查，不代表旧Milvus卷升级、供应链证明或全产品通过；默认基础设施保持原配置。
