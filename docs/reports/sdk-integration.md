# 多语言 SDK 验证记录

日期：2026-09-10。新增独立 Java / Python SDK，服务端业务和数据库结构未改变。

- Python：16项测试通过；Ruff 静态及格式检查通过；wheel 与源码包构建成功。
- Java：4项本机 HTTP 协议测试通过；Maven verify 与格式化完成，生成 SDK JAR。
- 覆盖：公共路径、认证头、请求键、multipart、索引/发布参数、搜索门槛、中文 SSE、缺少 done、服务端 error、错误JSON、提前取消与HTTP错误不重试/不重定向。Python另验证逐字节UTF-8分帧和公开OpenAPI路径/字段。
- Java运行环境：本机JDK25.0.2，编译目标Java21；Python本机3.12。尚未执行所有支持版本的兼容矩阵。

这些是客户端协议测试；Java用本机HTTP测试服务器，Python用明确的MockTransport，不能代替真实CareFlow+Embedding+Rerank+DeepSeek全链路验收。未发布到PyPI/Maven Central，未执行远端CI。安装方法、权限区别与示例见 sdk/README.md。
