# 本地制品核验记录

2026-09-11，源码快照`ea80232098f395eeafefe38551720848bd71219a`，版本0.1.0。制品位于本机`.local/artifacts/careflow-0.1.0-ea80232-2/`，总计约708MB；[逐文件SHA256、字节数和镜像ID](v1-53-local-artifacts.json)。没有推送Git、创建标签、上传软件包或镜像。

包含源码tar.gz、Java后端JAR、Java SDK JAR与POM、Python wheel与sdist、Web静态包、4个应用/可选模型Docker镜像的归档、Compose镜像覆盖文件、安装说明与许可声明。基础设施镜像、BGE权重、运行凭证和数据卷不在其中，因此不是完整离线环境备份。

实际检查：所有SHA256复核通过；`docker load`重新导入4个镜像成功且ID相同。Python wheel在独立虚拟环境安装后调用真实公共API和检索成功；Java JAR/POM安装到本地Maven仓库，独立消费者解析依赖并编译为Java21后访问真实API成功。Java SDK POM一并分发，避免丢失Jackson依赖。源码归档只取已跟踪文件，未包含`.env`、`.local`或Git目录。首次打包校验将tar根目录误判为非法，修正校验后使用新的目录重新打包，旧部分目录未覆盖。

构建记录：后端、Worker和可选模型使用Docker官方ECR镜像作为基础源；Web首次因Docker Hub鉴权连接超时失败，改用同一官方镜像的ECR来源后构建成功。应用源码与快照一致；后续报告提交不会回写此不可变制品清单。镜像只验证Linux/amd64。安装和迁移见[本地制品说明](../local-artifacts.md)。

**V1-53保持未完成**：本地可安装制品已经具备，但人工质量评估、安全发现与远端CI/保护仍未全部关闭。性能已由所有者调整为公开的部署容量限制，不再作为功能门禁。`release_ready=false`，不得将本记录解释为1.0正式版、完整安全验收或已公开发布。
