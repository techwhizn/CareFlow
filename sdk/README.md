# Java / Python 接入

SDK 0.1.0 对应 CareFlow 公共 API `/api/v1`。两种语言访问同一服务与同一权限体系；不直接连接 MySQL、Milvus 或内部 Worker。SDK 是仓库内预览版本，尚未发布到 PyPI / Maven Central。

## 能力与权限

| 能力 | Python（同步/异步） | Java |
| --- | --- | --- |
| 列出、创建知识库 | knowledge_bases / create_knowledge_base | knowledgeBases / createKnowledgeBase |
| 上传文件 | upload | upload |
| 查询文档版本与内容修订 | document_versions | documentVersions |
| 父子/FAQ上下文预览 | document_contexts / document_context | documentContexts / documentContext |
| 切片质量诊断与统计 | document_quality | documentQuality |
| 切片读取与正文修订 | document_chunks / edit_chunk | documentChunks / editChunk |
| 拆分/合并/启停/标签 | chunk_operation（ChunkOperation） | chunkOperation（ChunkOperation） |
| 修订与解析冲突 | chunk_changes / content_conflicts / resolve_content_conflict | chunkChanges / contentConflicts / resolveContentConflict |
| 整组FAQ新增与修订 | save_faq（FaqInput） | saveFaq（FaqInput） |
| 解除组关联 | detach_context | detachContext |
| 查询任务 | job | job |
| 配置版本与可用模型 | knowledge_configurations / configuration_models | knowledgeConfigurations / configurationModels |
| 保存、影响检查、发布配置 | create_knowledge_configuration / configuration_impact / publish_knowledge_configuration | createKnowledgeConfiguration / configurationImpact / publishKnowledgeConfiguration |
| 按新配置重处理 | reprocess | reprocess |
| 兼容旧索引绑定 | bind_configuration | bindConfiguration |
| 建立索引 | index | index |
| 发布版本 | publish | publish |
| 搜索 | search | search |
| 流式问答 | answer 上下文与事件迭代 | answer 事件回调 |

创建、上传、索引与发布需具备相应权限的个人凭证；应用 API Key 主要用于已授权知识库的搜索和问答。SDK 不提升权限。发布需传当前文档 revision 及用户核对过的内容版本 version_revision，版本必须 READY；SDK 不自动发布、不自动轮询任务，也不绕过失败状态。

base_url 是服务根地址，例如 `http://localhost:8080`，不含 `/api/v1`。令牌从环境变量或密钥管理服务注入；不要把令牌写进示例源码。生产接入使用 HTTPS。

FAQ保存与解除关联需传入核对内容时取得的版本 `revision`，并填写原因；遇到409需重新预览。人工FAQ不声明原文件位置，已发布版本须先复制草稿。策略、预算与迁移说明见[上下文切片](../docs/context-chunking.md)。

拆分合并需同时提供版本revision和每个目标片段revision；批量启停与标签也使用相同校验。Python拆分位置用 `careflow_sdk.content.utf16_offset(text, character_offset)` 将字符边界转成协议偏移。新解析中的人工修订冲突需显式选择处理方式，参见[编辑与对照契约](../docs/chunk-editing.md)。

## Python

Python 3.10+，安装仓库内包：

```bash
python -m pip install ./sdk/python
```

同步搜索与流式问答：

```python
import os
from careflow_sdk import Client, Query

with Client(os.environ["CAREFLOW_URL"], os.environ["CAREFLOW_TOKEN"]) as client:
    query = Query("CF-100 报 E404 怎么处理？")
    result = client.search(query)
    print(result["evidence"])
    with client.answer(query) as events:
        for event in events:
            if event.name == "delta":
                print(event.data["text"], end="", flush=True)
```

异步调用：

```python
import asyncio
import os
from careflow_sdk import AsyncClient, Query

async def main():
    async with AsyncClient(os.environ["CAREFLOW_URL"], os.environ["CAREFLOW_TOKEN"]) as client:
        result = await client.search(Query("设备如何复位？"))
        print(result["evidence"])
        async with client.answer(Query("设备如何复位？")) as events:
            async for event in events:
                if event.name == "delta":
                    print(event.data["text"], end="", flush=True)

asyncio.run(main())
```

提前退出 `with` / `async with` 会关闭响应。异步上传的文件读取仍是同步本地文件读取，网络使用异步 HTTP；不承诺所有磁盘操作均不阻塞事件循环。连接生命周期遵循 [HTTPX 官方异步说明](https://www.python-httpx.org/async/)。

## Java

Java 21+，在本地 Maven 仓库安装：

```bash
mvn -f sdk/java/pom.xml install
```

业务项目加入依赖（SDK 运行时不依赖 Spring）：

```xml
<dependency>
  <groupId>com.careflow</groupId>
  <artifactId>careflow-sdk</artifactId>
  <version>0.1.0</version>
</dependency>
```

```java
import com.careflow.sdk.CareFlowClient;
import java.net.URI;

var client = new CareFlowClient(
    URI.create(System.getenv("CAREFLOW_URL")), System.getenv("CAREFLOW_TOKEN"));
var query = new CareFlowClient.Query("CF-100 报 E404 怎么处理？");
var result = client.search(query, null);
System.out.println(result.get("evidence"));
client.answer(query, null, event -> {
    if (event.name().equals("delta")) System.out.print(event.data().path("text").asText());
});
```

示例调用放在 `main` 或业务方法中，处理/声明 IOException。Java SDK 使用阻塞调用，可在调用方虚拟线程运行；回调抛异常会关闭连接。默认连接与读取超时120秒，可用第三个构造参数（毫秒）修改；读取超时是无数据等待时间，不是总回答时长。

## 错误、幂等与完成语义

- HTTP 失败：Python ApiError / Java ApiException 暴露 status、code 和请求 ID（Python request_id / Java requestId），异常文本不包含服务端正文。网络异常由底层 HTTP/IO 异常报告。
- 流式 `error`、非法事件、非 SSE 响应、未收到 `done` 就断流：抛异常，不能把已输出的部分文字记为成功答案。Python 为 StreamError，Java 为 IOException。
- SDK 不自动重试 HTTP 错误、不跟随重定向。POST 默认生成请求键；可显式传入 idempotency_key（Python）或 key（Java）。查询重复键会返回409，当前服务端不重放旧结果；不要将更换键重试当成无成本恢复。
- 搜索和问答返回字典/JsonNode；请求 Query 与流事件有类型。完整响应 DTO、更多管理接口封装和包仓库发布仍待后续完善。

## 验证

```bash
PYTHONPATH=sdk/python uv run --project worker pytest sdk/python/tests -q
uv run --project worker ruff check sdk/python
uv run --project worker ruff format --check sdk/python
mvn -f sdk/java/pom.xml spotless:check verify
uv build --project sdk/python --out-dir sdk/python/dist
```

Python 使用明确的 MockTransport，Java 使用本机 HTTP 测试服务器；测试路由、认证头、multipart、请求键、HTTP错误、SSE中文/分帧/终止和提前退出。另检查公开 OpenAPI 中的路径与请求字段。它们是客户端协议验证，不替代真实模型和完整业务联调。

## 发布参数升级（V1-15）

本次仓库预览版将 `version_revision` 设为必填，旧请求缺少此字段返回400。Python同步/异步 `publish(document_id, version_id, revision, version_revision, ...)`；Java `publish(documentId, versionId, revision, versionRevision, key)`。这是预览SDK的签名变更，调用方需要同步升级，不能自动填入最新修订号绕过预览冲突检查。

通过 `document_versions(document_id)` / `documentVersions(documentId)` 获取版本列表，在核对目标版本内容时保留其 `revision`，发布时作为 `version_revision` 传入；文档 `revision` 来自文档列表或详情。409时重新读取并核对内容，再决定是否发布。READY仅表示处理就绪，不会自动发布。

搜索与问答的 Query.filters 支持类型化元数据条件；Python 使用 MetadataFilter，Java 使用 MetadataFilter/MetadataField/MetadataOperator。字段、值类型和组合规则见 [过滤契约](../docs/metadata-filters.md)。

任务查询返回新增的索引计数及`index_usage`摘要。缺失供应商用量以未知调用数表示，不推断为0；详见 [增量索引与用量](../docs/incremental-indexing.md)。


清理状态：Python同步/异步客户端使用 `cleanup_requests()`，Java使用 `cleanupRequests()`；失败恢复后分别调用 `retry_cleanup(request_id, reason, idempotency_key=...)` / `retryCleanup(requestId, reason, key)`。接口只返回当前身份有权查看的清理元数据；重试不跳过服务端范围校验，应用凭证不能调用管理清理接口。

搜索响应新增证据Token总数、上限、Tokenizer及同版本扩展来源字段，两种SDK保留这些字段；具体语义见 [证据整理](../docs/evidence-context.md)。

生成流还可返回`usage`事件，计数来自供应商，缺失字段表示未知；事件不表示回答已完成。取消和迁移说明见[流式问答](../docs/answer-streaming.md)。

运维汇总可通过Python `operations_status()`或Java `operationsStatus()`读取，仅OPS/OWNER/ADMIN允许；OPS不能读取知识内容。详见[访问边界](../docs/access-boundaries.md)。

## 连续追问与历史

Python `create_conversation(knowledge_base_ids=(kb_id,))` / Java `createConversation(null, List.of(kbId))` 创建会话。将返回的 `id` 传给 Query 新增的 `conversation_id`；保持应用和知识库集合一致。省略 ID 时自动创建新会话，旧调用保持兼容。

Python `conversations()`、`conversation(id)`、`answer_history(conversation_id=id)`、`saved_answer(id)` 提供列表、会话历史和带引用的答案；异步客户端同样支持。Java 对应 `conversations()`、`conversation(id)`、`answerHistory(id)`、`savedAnswer(id)`。所有历史读取均重新授权，撤权后可能隐藏或返回 404。详见[会话协议](../docs/conversations.md)。
