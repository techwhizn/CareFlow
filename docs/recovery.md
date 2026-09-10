# 隔离冷备份与恢复

本工具适用于默认单机Compose部署，需要Python 3.12、Docker与OpenSSL 3。它补充原MySQL逻辑备份，不提供在线一致性快照或高可用。操作期间必须独占维护窗口，禁止其他终端启动服务或写入卷。

## 创建备份

先停止整个部署，包括API、Worker和存储服务。工具核对九个默认服务均停止、五个卷属于该项目且没有运行容器挂载，未知布局拒绝处理。宿主机或仓库外程序的写入需要管理员另外停止。

```bash
# 密钥路径必须位于备份目录之外；示例路径需替换为受控密钥存储。
python3 scripts/cold-backup.py keygen --key-file /secure/careflow-backup.key
docker compose stop
python3 scripts/cold-backup.py capture --project careflow \
  --directory /backups/careflow-20260911 \
  --key-file /secure/careflow-backup.key \
  --helper-image <本地已构建的Worker镜像> \
  --configuration .env --configuration compose.yml \
  --configuration deploy/milvus-user.yaml
python3 scripts/cold-backup.py verify \
  --directory /backups/careflow-20260911 --key-file /secure/careflow-backup.key
```

自定义部署还需传入Compose覆盖文件。工具只接受本地已存在的辅助镜像，不拉取镜像；读取卷的容器无网络、根文件系统只读。保存MySQL、S3、Milvus、etcd和RabbitMQ卷，配置单独加密；数据库包含冻结模型身份，外部模型权重仍需按固定修订保留。镜像内容不进入备份，只保存实际镜像ID；另行保留兼容镜像制品。

卷内容直接管道加密，默认不产生明文tar。使用随机256位密钥、OpenSSL AES-256-CBC/PBKDF2加密，各文件密文摘要由独立派生的HMAC密钥认证。必须先验证整个清单及全部密文，再解密；不允许绕过校验直接运行解密命令。密钥只通过权限受限文件传给OpenSSL，不写入命令参数或备份。丢失密钥无法恢复。失败归档保留未完成状态，不能作为恢复输入。

当前冷备份目录不属于旧版 `managed_backups.py` 的MySQL自动淘汰范围。应在备份存储配置30天生命周期；没有生命周期的本地测试目录需管理员按记录清理。不可把此工具描述为已经实现自动保留期管理。

## 恢复存储，保持访问关闭

```bash
python3 scripts/cold-restore.py \
  --directory /backups/careflow-20260911 --key-file /secure/careflow-backup.key \
  --project careflow-recovery-20260911 \
  --configuration-output /secure/careflow-recovery-20260911 \
  --helper-image <本地已构建的Worker镜像>
```

目标必须是不同的新项目，不能已有容器或同名卷。恢复只创建存储卷与权限受限的配置目录；**不会创建或启动应用容器**。失败时保留恢复回执与已创建卷，不能对部分恢复直接重试覆盖。管理员核对回执后可选择另一个空目标重新演练。恢复配置包含真实凭证，不能上传Git或打印日志。

`STORAGE_RESTORED_ACCESS_CLOSED` 仅表示存储提取完成，不代表产品可用。数据库必须先在无公共端口的隔离网络启动，使用相同兼容镜像核对数据、迁移、原文件摘要及模型身份。在开放任何API、重建索引或恢复Worker前，仍须重放备份之后最新的删除、权限收窄、成员禁用、应用绑定变化和凭证撤销。无法确认最新安全状态时保持全部业务入口关闭。日志前滚见下文；完整恢复验收尚未完成，工具不提供解除该门禁的命令。

演练应记录备份起止、最后一致性时刻、恢复起止、可用性核验时刻、RPO/RTO、失败及处理方式。禁止将卷提取耗时冒充完整RTO，或将空环境启动冒充历史升级验证。

## 重放备份之后的完整数据库日志

冷备份记录停止时最后一个binlog文件的摘要。后续增量要求该边界文件保持完整，并有连续的新日志；从同一来源导出至当前停止点，完整重放事务，不自行筛选权限相关SQL。先构建[匹配版本的mysqlbinlog镜像](../tools/mysql-recovery/README.md)。

```bash
# 源部署进入新的独占维护窗口并全部停止后执行。
python3 scripts/mysql-recovery-log.py export \
  --base-directory /backups/careflow-20260911 \
  --directory /backups/careflow-increment-20260912 \
  --key-file /secure/careflow-backup.key --project careflow
# 目标只启动数据库，使用 --network none 且不映射端口；不得启动Worker。
python3 scripts/mysql-recovery-log.py apply \
  --directory /backups/careflow-increment-20260912 \
  --key-file /secure/careflow-backup.key \
  --configuration-output /secure/careflow-recovery-20260911 \
  --mysql-container <隔离恢复数据库容器名>
```

工具校验目标挂载的是对应新恢复卷、镜像匹配且没有网络或公开端口。执行前写入STARTED回执；成功、失败或中断后均不能对同一目标重复重放。失败必须从新空卷恢复，不能忽略SQL错误继续。原数据快照与增量均先验签，再在单个MySQL会话中执行。

[真实演练](reports/v1-46-binlog-replay-real.json)已经验证备份后公共API删除、文档ACL收窄和凭证撤销的重放；旧凭证401、删除文档及下载404、历史答案隐藏。工具仍不开放业务入口：新增文件的存储增量、索引核对和最后接受写入的截止点必须继续确认。单机停止点导出不能冒充异地灾难下的持续日志归档。
