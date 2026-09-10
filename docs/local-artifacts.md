# 本地安装制品与最终门禁

当前制品标记为`0.1.0`开发增量。源码、JAR、Python wheel/sdist、Web静态目录与Docker镜像可以在本机准备和校验；这不创建公开版本，不修改Git标签或远端，也不意味着1.0验收完成。

## 构建和校验

在干净工作区按CONTRIBUTING完成检查，再执行：

```bash
mvn -B -f backend/pom.xml package
mvn -B -f sdk/java/pom.xml package
uv build --project sdk/python --out-dir sdk/python/dist
npm run build --prefix web
docker compose build
docker build -t careflow-local-models:local-candidate tools/local-models
# 输出目录仅用于本机制品；源码仅取Git已跟踪内容。
mkdir -p .local/artifacts
git archive --format=tar.gz --prefix=CareFlow/ \
  -o .local/artifacts/careflow-source.tar.gz HEAD
```

每次实验使用新目录，记录源码提交、实际镜像ID、目标架构与SHA256。校验值验证字节一致性，不是签名或来源证明。制品与源码可能包含公开的合成测试数据，不能混入`.env`、`.local`、模型凭证或备份资料。`git archive`不会携带未跟踪的本地设置。

安装Python wheel：`python -m pip install ./careflow_sdk-0.1.0-py3-none-any.whl`。Java SDK JAR可在本地Maven仓库安装：

```bash
mvn install:install-file -Dfile=careflow-sdk-0.1.0.jar \
  -DgroupId=com.careflow -DartifactId=careflow-sdk -Dversion=0.1.0 -Dpackaging=jar
```

后端JAR通过`java -jar knowledge-platform-0.1.0.jar`运行，先按[配置](../.env.example)在进程环境中设置数据库、队列、对象存储和Worker连接。Web静态文件需要[同源API代理](../web/nginx.conf)，不能直接用文件协议替代服务器。导出的Docker镜像可用`docker load -i careflow-images.tar`导入，再用附带的Compose镜像覆盖文件启动；模型权重和基础设施镜像需按文档另行准备。镜像包不包含数据卷、运行凭证或BGE权重，不是全离线环境备份。

## 兼容性与迁移

Java最低21；SDK已在21/25核验。Python SDK在3.10/3.12/3.14核验；Worker为3.12，Node构建为22。当前本机容器产物仅验证Linux/amd64，未宣称ARM兼容；浏览器验证为Chromium，见[兼容性矩阵](reports/v1-49-browser-compatibility.md)。

数据库按Flyway顺序升级至V36，禁止修改已应用迁移。已有模型身份、配置版本和发布修订的迁移要求分别见[配置](knowledge-configurations.md)、[增量索引](incremental-indexing.md)和[文档发布](document-publications.md)。升级前保存经过校验的停写备份；回退恢复整套匹配的旧数据/镜像及删除撤权记录，不能只降低JAR版本，见[恢复手册](recovery.md)。候选MySQL/RabbitMQ/S3升级尚未进入默认部署，不混用候选镜像与历史恢复工具。

## 尚未关闭的1.0门禁

- 120题参考证据及生成答案人工评审未完成，不用模型自评替代。
- 十万分块、10租户、5QPS性能目标尚未达到；本机CPU重排容量不足，见[实测](reports/v1-45-performance.md)。
- 基础镜像/基础设施仍有待处理的安全发现；远端CI和分支保护未验证，见[安全检查](reports/v1-50-container-scan.md)。
- 上述门禁未关闭时，V1-42/V1-53不得标记为正式版完成。当前本地包只能用于开发、复现与验收。

人工开通套餐、单机部署、非连续跨存储备份及意图接口范围等约束仍适用。第三方模型和基础设施按各自许可证使用，本项目Apache-2.0不会变更这些许可证。
