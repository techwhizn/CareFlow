# 人工切片编辑与解析冲突

仅未发布且PARSED、READY或FAILED的内容版本可修改；执行中的任务、已发布版本和陈旧修订均拒绝。模型Token校验在事务外执行，提交前重新验证原凭证、角色、权限、内容版本及每个目标切片修订。修改使版本回到PARSED并递增revision。

## 操作

`POST /api/v1/document-versions/{version}/chunk-operations` 接收版本 `revision`、`action`、`chunks:[{id,revision}]` 与 `reason`。每批最多100个目标，同一版本，不允许重复目标。

- `SPLIT`：一个目标和1–19个严格递增的 `split_offsets`。偏移使用Java/JavaScript UTF-16边界，不能切开代理对；Python调用者对含非BMP字符的内容须先换算。Web通过在内容中放置光标选择，不要求用户计算偏移。拆分保留完整文本，不自动删除空白，空片段和模型预算超限显式拒绝。
- `MERGE`：2–20个相邻、启用状态相同的片段，按文档顺序合并。重新校验总Token，标签取并集且最多20个。父子/FAQ关联须先解除，防止破坏组关系。
- `SET_ENABLED`：`enabled` 布尔值；无需调用模型。
- `TAGS`：`tags` 最多20个、每个80字符，替换选中片段的标签，空数组清除；无需模型调用。

拆分合并保留原始提取内容及来源范围记录，明确标为人工修订，不伪造新内容逐字对应的原文位置。单次合并来源位置最多60000字节、原始提取文本最多240000字符，超出需分批处理。批量修改全成或全败。复制草稿保留启用状态和标签。整组FAQ修订保留统一的启用状态及标签并集；混合启用状态或标签超过20个时要求先整理。

`PUT /api/v1/chunks/{id}` 仍支持单片段正文/启用修改；正文上限提升到10000字符，最终还受配置与真实模型Token预算限制。`GET /document-versions/{version}/chunk-changes?page=0` 每页50条修改前快照、结果ID、操作者、时间与原因。

## 重新解析对照

重新处理或上传新版时，在同一事务中保存上个源版本的人工内容、标签和启用状态快照。未处理冲突会传入派生草稿/再次解析，不能通过复制版本绕过。后续源版本变化不改写已经保存的对照。

`GET /document-versions/{version}/content-conflicts?page=0` 每页100条，需当前版本edit和源版本read权限。只提供原始提取文本完全相同的新切片候选（最多20个）；不以语义猜测自动覆盖。页面可对照旧原文、旧人工内容和新解析。

`POST /document-versions/{version}/content-conflicts/{id}/resolution` 传版本 `revision`、`reason`，选择：

- `KEEP_NEW`：确认保留当前内容；也用于在工作台完成手动修订后的确认。
- `APPEND_OLD`：重新校验旧人工内容在新配置下的预算，作为无当前原文件定位的人工补充，保留旧标签/启用状态。
- `APPLY_TO_CHUNK`：传 `target:{id,revision}`，采用旧人工正文/标签/启用状态，保留目标新原文以便对照。有关联上下文的目标须先处理组关系。

操作记录可审计；未处理完返回409 `UNRESOLVED_CONTENT_CONFLICTS`，阻止索引和发布。处理结果不自动索引或发布。新文件结构不同、FAQ组关系和部分片段语义冲突需管理员判断，模型不替管理员作选择。

Java与同步/异步Python SDK提供类型化请求：ChunkRef、ChunkOperation、ChunkEdit、ConflictResolution；对应列表、操作、修改记录及冲突处理方法见SDK指南。

## 升级

V19新增标签、操作记录、冲突快照表，并将原文提取字段扩大为MEDIUMTEXT。V20新增内容版本创建序号，用于同一秒内创建多个版本时稳定排序；历史同时间版本的真实先后无法追溯，不作伪造。升级前备份数据库，暂停写入和消费者，升级Java后恢复。旧修订日志继续保留；没有把旧操作伪造成新操作记录。恢复须包含新表并保持文档、内容版本与索引一致。

MySQL连接统一UTC会话及瞬时转换，避免本机与数据库时区不同导致修订记录或到期时间偏移。历史Java写入时间的核对要求见[ADR 013](adr/013-content-review-and-database-clock.md)。
