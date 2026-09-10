# 第三方组件与分发声明

CareFlow 自有代码采用 Apache-2.0。此许可不替换依赖、容器基础系统或模型权重的许可。维护者为良枫，项目地址为 https://github.com/techwhizn/CareFlow。

## 清单范围

[依赖清单](docs/licenses/)保存实际解析的 Java 依赖、Linux Worker 与模型服务的 Python 分发包，以及前端生产依赖的声明。Java 清单包含测试依赖；Python 清单包含运行环境中的安装工具。清单是声明记录，不把名称相似的许可证自动视为等价。

前端分发文件 `THIRD-PARTY-NOTICES.txt` 保留 React、React DOM、Scheduler 和 Phosphor Icons 的完整 MIT 文本及版权声明。Java 制品保留依赖 JAR，不剥离其中的 LICENSE/NOTICE；Python 镜像保留各包的 dist-info 许可文件。重新打包时应保留这些文件。

## 外部服务与模型

默认部署引用独立上游镜像，不把其许可证改写为 CareFlow 的 Apache-2.0：

| 组件 | 上游许可入口 |
| --- | --- |
| MySQL 8.4.6 | [GPLv2 及附加条款](https://github.com/mysql/mysql-server/blob/mysql-8.4.6/LICENSE) |
| RabbitMQ 4.1.4 | [MPL-2.0 与随附声明](https://github.com/rabbitmq/rabbitmq-server/blob/v4.1.4/LICENSE) |
| MinIO 固定版本 | [AGPL-3.0](https://github.com/minio/minio/blob/RELEASE.2025-07-23T15-54-02Z/LICENSE) |
| Milvus 2.6.1 | [Apache-2.0](https://github.com/milvus-io/milvus/blob/v2.6.1/LICENSE) |
| etcd 3.5.18 | [Apache-2.0](https://github.com/etcd-io/etcd/blob/v3.5.18/LICENSE) |

可选 BGE 模型固定版本与下载方式见[模型服务说明](tools/local-models/README.md)。BGE-small-zh-v1.5 和 BGE-reranker-base 的上游模型声明为 MIT；模型文件不包含在源码仓库中，下载后保留上游许可与模型卡。DeepSeek 通过用户配置的 API 使用，服务条款由其提供方管理，不包含模型权重分发授权。

容器基础系统、OCR 引擎及系统库的版权文件保存在镜像的 `/usr/share/doc` 等上游位置。当前清单不冒充完整操作系统 SBOM。修改或对外分发第三方镜像时，需要按对应上游条款保留源代码获取方式、修改记录和声明；本项目当前仅进行本地构建验证，没有发布这些镜像。
