# MySQL恢复工具镜像

默认MySQL服务器镜像只包含最小服务与部分客户端，不包含mysqlbinlog。本目录从匹配版本的官方客户端RPM提取mysqlbinlog及其完整上游许可文件，保留RPM签名校验；失败不绕过校验。

```bash
docker build -t careflow-mysql-recovery:8.4.6 tools/mysql-recovery
docker run --rm --network none careflow-mysql-recovery:8.4.6 --version
```

当前实际验证Linux amd64和MySQL 8.4.6；其他架构显式失败，不能套用x86二进制。`MYSQL_IMAGE`构建参数可指向同版本受信镜像镜像源，首次构建需访问OracleLinux包源与MySQL官方RPM源；恢复运行本身不需要网络。源码包与许可见[MySQL官方仓库](https://repo.mysql.com/yum/mysql-8.4-community/el/9/x86_64/)，镜像不包含CareFlow数据。

使用方式见[隔离恢复](../../docs/recovery.md)。服务器升级时单独验证匹配工具、日志格式和回滚方式，不静默更换解码器。
