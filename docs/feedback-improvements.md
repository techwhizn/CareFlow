# 回答反馈与知识改进

## 用户反馈

`POST /api/v1/answers/{id}/feedback` 接收：

- `feedback`：`helpful` 或 `incorrect`。
- `reason`：`WRONG_ANSWER`、`WRONG_SOURCE`、`MISSING_KNOWLEDGE`、`OUTDATED`、`OTHER`。旧调用省略原因时差评归为 OTHER。
- `comment`：最多 2000 字符的补充说明。
- `revision`：当前 `feedback_revision`，新页面和 SDK 使用该值避免覆盖并发反馈；旧客户端可省略，保持兼容。

仅能反馈自己仍有权访问的完整答案；每次检查当前和历史依赖。返回最新反馈与修订。审计只记录反馈类别，不把说明正文写入审计日志。

## 创建和处理任务

`POST /api/v1/improvements` 传 `source_kind`（ANSWER / NO_RESULT）、`source_id`、`knowledge_base_id`、`description`。

ANSWER 来源必须是已记录差评的本人答案；NO_RESULT 必须是本人真实查询记录，状态为 NO_MATCH 或 BELOW_THRESHOLD。模型不可用、降级或仍有证据的查询不能伪装成“知识缺失”。旧答案没有查询记录时需重新提问。

Java 在完成检索后保存查询记录，检索响应返回 `query_record_id`，问答 `done` 同样返回该字段。记录包含问题、知识范围、配置 ID、应用修订、查询条件、发布版本与证据依赖；不保存模型密钥或额外调试正文。资料正文位于已有证据/答案快照中。

任务唯一键为租户、目标知识库、来源类型和来源 ID；同来源重复提交返回已有任务。任务创建代表用户主动将问题和反馈交给该知识库管理人员。显式查询范围不能重定向到无关知识库，应用来源也必须满足当前应用绑定。

- `GET /improvements`：最近 100 个当前可见任务。
- `GET /improvements/{id}`：任务、原始查询、关联答案和证据版本。
- `GET /improvements/{id}/assignees`：有权处理该任务和访问全部资料的可选负责人。
- `PUT /improvements/{id}`：传 `revision`、`state`、`assignee_id`、`resolution`。

状态为 OPEN、IN_PROGRESS、RESOLVED、DISMISSED；关闭必须填写处理说明，可重新打开。处理和分配要求知识库编辑权限及全部关联资料读取权限；用户只能查看自己的已提交任务。并发修订不匹配返回 409。

应用凭证反馈/建任务要求 ANSWER；读取关联答案或任务要求 READ，处理权限仍保留给成员。OPS 不可访问内容或任务。

## 生命周期和隐私

任务查看重新校验资料和应用授权。资料禁用、撤权或删除会隐藏受影响任务；物理清理删除相关查询记录、答案及级联任务。目标知识库物理清理也清理其任务与显式范围记录。查询问题和用户说明是持久化业务数据，备份和访问控制与知识库数据库一致。

“已解决”是有权管理员记录的处理决定，不自动证明知识已经发布或评估通过；验收仍需核对变更及评估结果。
