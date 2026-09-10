# Java依赖漏洞修复记录

2026-09-11，使用 [OSV批量查询API](https://google.github.io/osv.dev/post-v1-querybatch/) 检查Maven实际解析的运行依赖，初始后端88个包中17个关联已知公告。Spring Boot升级3.5.16、MinIO客户端升级8.6.0；后者修复见[上游安全公告](https://github.com/minio/minio-java/security/advisories/GHSA-h7rh-xfpj-hpcm)。

Boot的依赖清单仍包含部分旧版本，显式采用Tomcat10.1.59、RabbitMQ客户端5.33.1、Jackson2.21.6、Log4j2.25.5、Commons Lang3.18.0、BouncyCastle1.84、Netty4.1.138.Final。上游后续BOM覆盖这些修复版本后，应移除不再需要的覆盖，而不是永久保留分叉依赖清单。Java SDK与后端同步Jackson版本。

升级后实际解析90个后端运行依赖及3个Java SDK运行依赖，OSV均为0项已知公告；后端178项测试和SDK23项测试通过，包含公共OpenAPI快照对比。完整坐标与时间见[报告](v1-50-java-dependencies.json)。执行环境为JBR25.0.2，编译目标21；这不代替JDK21容器与运行服务验证。

新增审计脚本遇到空依赖清单、未识别坐标、查询错误或分页不完整会失败；两项回归测试覆盖这些行为与跨页公告合并。审计只发送公开包坐标及版本，不发送代码或凭证。CI加入后端和SDK审计步骤；本地结果不意味着远端CI或分支保护已启用。
