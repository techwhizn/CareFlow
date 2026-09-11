# 扩展指南

扩展应保持“知识库底座”和“应用层”边界。底座提供文档、切片、索引、检索、引用、权限和配置版本；业务意图、流程编排和 Agent 工具属于应用层，不应写入通用知识库服务。

## 添加解析器

解析器位于 Python Worker。输入必须经过文件大小、页数、像素、解压、超时和资源限制；输出使用现有解析类型，包含稳定的来源定位信息。解析失败返回明确错误码，不返回部分伪造成功。为新格式添加合成样例、损坏文件和超限文件回归测试，并更新 [解析文档](parsing.md)。

## 添加模型适配器

适配器必须实现已有 Embedding、Rerank 或 Generation 协议，校验模型名称、响应结构、维度、有限分数、SSE `done` 事件和超时。模型输出不能授予权限或直接触发业务动作。凭证由 Java 模型配置管理，Worker 只接收最小化、已授权的运行配置。同步更新 [内部协议](internal-contracts.md) 和模型连接检测。

## 添加检索策略

检索策略必须在授权过滤之后工作，保留 dense/BM25/RRF/Rerank 阶段记录和配置版本。新增过滤字段使用类型化白名单，不能接受任意 SQL、Milvus 表达式或客户端租户范围。新增策略要提供命中、排除、无答案和越权回归用例，并更新 [检索文档](metadata-filters.md) 与 ADR。

## 添加应用能力

应用层通过公共 Java API 使用知识库。应用凭证、限流、用量和发布状态由 Java 管理；意图识别和流程编排在应用内配置。应用不能绕过用户或租户授权，也不能把模型文本当作权限决定。公共协议变化必须同步 Java/Python SDK、OpenAPI 和迁移说明。

## 提交前检查

```bash
mvn -B -f backend/pom.xml spotless:check verify
uv run --project worker ruff check worker scripts
uv run --project worker ruff format --check worker scripts
uv run --project worker pytest worker/tests -q
npm test --prefix web
npm run build --prefix web
```

涉及 SDK、数据库、容器或安全边界时，按 [贡献指南](../CONTRIBUTING.md) 增加相应检查和报告。
