# 知识问答应用配置

首版应用类型为知识问答。应用的负责人、绑定、检索策略、生成偏好归属于应用；知识库仍是独立底座。

## 负责人和版本

创建应用可传 `owner_id`，默认创建者。负责人必须是本租户有效成员，负责人身份不自动授予知识或开发权限。

`POST /applications/{id}/configurations` 保存不可变草稿；`POST /applications/{id}/configuration-publications` 用配置 ID 和当前应用修订发布或回滚。保留 `PUT /applications/{id}/publication` 兼容入口，它在同一事务内保存并发布完整配置。发布记录在 `GET /applications/{id}/publications`。

配置含 `knowledge_base_ids`、`revision`、`owner_id`、`allow_degraded`、可选 `retrieval`、可选 `models` 和 `answer_policy`。发布必须检查当前成员资料读取权限、应用读取授权、有效负责人和模型地址策略。回滚复用原始配置和模型快照，也执行这些当前检查。

## 模型和查询策略

`models` 同时指定 Rerank、生成模型的档案 ID 和观察到的修订号。保存时复制加密凭证和模型属性；后续修改档案不会静默改变已保存版本。公开接口不返回加密或明文密钥。知识库和应用复用 ModelSnapshotService 实现快照与解密策略。

应用指定查询模型时，可为多个查询配置不同的知识库提供统一 Rerank/生成模型。查询 Embedding 仍严格按每个知识库已索引版本的不可变模型身份分组，不能用应用模型重写索引身份。

应用 `retrieval` 指定 mode、limit（1–6）、minimum_rerank_score、allow_degraded。显式应用策略优先于知识库查询默认值，请求只能进一步收紧证据数量与门槛；请求 mode 与已发布应用策略不一致返回 `APPLICATION_POLICY_MISMATCH`。启用降级需同时满足应用与查询策略允许。

省略应用模型时继承知识库查询模型，多个知识库模型不一致仍会明确报配置冲突；只指定模型而未指定策略时使用 hybrid、6 条证据、无固定评分门槛。旧三字段配置继续兼容。

## 回答约束

`answer_policy` 是受控字段，不能替换系统权限或证据规则：

| 字段 | 可选值或范围 |
| --- | --- |
| language | auto / zh / en |
| style | concise / standard / detailed |
| maximum_output_tokens | 128–2048 |
| history_rounds | 0–6 |
| history_tokens | 0–3000 |

历史窗口在 Java 选择、Worker 生成入口再次验证；供应商输出限制随请求发送。过低输出上限可能导致引用截断，仍按未完成处理。语言和风格属于模型输出偏好，引用合法性由服务强校验。

## 页面与客户端

应用中心提供负责人、模型、策略、问答约束、草稿、发布和回滚。模型目录与负责人目录仅开发管理角色可读；`GET /applications/available` 只列出当前主体至少能读取一个绑定知识库的已发布应用。

搜索/问答传 `application_id`；应用凭证自动使用自身应用。问答页切换应用新建会话，不能把会话转给另一个应用。检索结果新增 `application_configuration_id`，与应用修订、处理配置共同回溯本次行为。

Java/Python SDK 提供 ApplicationConfiguration、模型引用、AnswerPolicy 以及创建/保存/发布/查看历史方法；所有调用经过公共 Java API。
