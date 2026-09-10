# API 使用与错误约定

静态 OpenAPI 见 `openapi.json`，运行中的 `/v3/api-docs` 为对应运行版本。API Key 与个人凭证使用 `Authorization: Bearer …`。所有服务端权限以当前数据库为准，忽略客户端自行声明的企业和用户身份。

## 关键流程

1. `POST /api/v1/bootstrap`：仅持部署初始化密钥调用，创建首个企业及所有者，只能成功一次。后续多企业自助注册尚未实现。
2. `POST /knowledge-bases`：名称与说明。
3. `POST /knowledge-bases/{id}/documents`：multipart `file`、`Idempotency-Key`；返回文档、版本和任务 ID。
4. `GET /jobs/{id}`：阶段、状态、次数和安全错误码。
5. `GET /document-versions/{id}/chunks`：按页读取。修改使用 `PUT /chunks/{id}`，携带 revision、content、enabled、reason。
6. `POST /document-versions/{id}/index`：触发真实索引；未就绪不能发布。
7. `POST /documents/{id}/publications`：携带 version_id 和文档 revision；选择旧版本同样经过当前授权检查。
8. `POST /retrieval/search`：query、mode（hybrid/semantic/keyword）、limit（1–20，最终证据最多 6）、knowledge_base_ids、可选 application_id/debug。没有授权发布内容返回空证据；依赖故障返回 503。
9. `POST /answers`：同样请求字段，SSE 事件为 start/status/delta/citations/done/error。消费者必须以 done 为完成标志，连接结束不代表回答完成。

## 应用配置

`POST /applications/{id}/configurations` 只保存草稿；`GET /applications/{id}/configurations` 列出版本；`POST /applications/{id}/configuration-publications` 发布选定版本，也用于回滚。请求携带 configuration_id 和当前 application revision。每次发布重新检查知识库读取授权，旧配置不恢复已撤销权限。

## 权限与密钥

资源授权 `PUT .../permissions` 是 **全量替换**，不是增量合并。使用 `grants: {subject_id: [read,download,edit,publish,manage]}`，并携带资源 revision。知识库创建者默认可管理；文档显式 ACL 会进一步收窄创建者权限。当前单主体管理表单已提示替换影响，多主体可通过 API 提交。

应用凭证默认 90 天到期；`DELETE /credentials/{id}` 立即撤销。轮换采用先创建新凭证、迁移调用方、再撤销旧凭证的流程。当前没有可配置的凭证动作 scope UI。

## 幂等边界

上传的 Idempotency-Key 在同租户任务中唯一，重复请求返回既有任务并复核访问权限；客户端应为不同上传生成不同 key。V5起上传保存请求指纹（知识库、创建/替换目标、文件名、内容摘要）；同key不同请求返回409 IDEMPOTENCY_CONFLICT。旧任务没有指纹时不重放，返回冲突并要求核对原任务。

查询和问答对同主体、同 key 的重复请求返回 409，不会重复预占；当前不回放旧响应。结算用同一事件 ID 幂等执行。进程中断后的过期预占还需要核对处理，不应把此版本作为完整账务系统。

## 错误

400 INVALID_ARGUMENT；401 UNAUTHENTICATED；404 NOT_ACCESSIBLE；409 VERSION_CONFLICT/NOT_READY/CONFLICT；413 FILE_TOO_LARGE；429 QUOTA_EXCEEDED；503 MODEL_OR_RETRIEVAL_UNAVAILABLE/SERVICE_UNAVAILABLE。错误包含 request_id，日志只记录请求 ID 与异常类型。

## 检索相关性门槛

搜索与问答请求支持可选 `minimum_rerank_score`（有限数值）；省略或 null 保持原有行为。按重排供应商的原始 score 判断，大于等于门槛才入选；分数不是统一概率，不同模型必须重新评估门槛。

显式设置门槛时，降级重排、缺失或非有限分数均不能入选。过滤后无证据的问答沿用无依据拒答流程，不调用生成模型。该设置目前按请求传入，尚未保存为知识库或应用配置版本。

搜索响应回显 `minimum_rerank_score`；有调试权限且 debug=true 时返回 `excluded: [{id, reason}]`。原因包括 BELOW_MINIMUM_SCORE、SCORE_UNAVAILABLE、DOCUMENT_LIMIT、CONTEXT_LIMIT、RESULT_LIMIT。只暴露已通过授权的候选，重复 ID 不重复展示。最终证据最多六条、每文档最多三条、内容总计最多6000字符的约束仍然有效。

## 模型连接注册（V1-01）

仅OWNER/ADMIN访问：`GET/POST /api/v1/model-profiles`、`PUT /api/v1/model-profiles/{id}`、`GET /api/v1/model-profiles/policy`。

请求字段：name、kind（GENERATION/EMBEDDING/RERANK）、base_url、model、model_revision、dimensions、external_processing、api_key、revision。Embedding必须有不可变版本和维度，其他类型dimensions为null。api_key为null时保留、空串时清除、非空时替换；切换地址/类型时必须重新输入或明确清除。响应只有key_configured，不包含api_key或密文。

地址必须匹配部署批准名单；未授权地址或外发范围不符返回400 MODEL_ENDPOINT_NOT_ALLOWED；主密钥不可用时保存非空密钥返回503 MODEL_KEY_STORAGE_UNAVAILABLE；并发修订返回409。注册不触发网络调用，也不切换当前运行模型；真实检测与配置绑定由后续工作完成。

## 企业开通与成员生命周期（V1-04）

部署管理员可调用 `POST /api/v1/enterprises`，提供 `X-Bootstrap-Token`、`Idempotency-Key`及 `{name, owner_name}`，开通新企业并取得一次性展示的所有者凭证。该接口不接受普通成员权限替代开通密钥，不提供跨企业资料查询。重复请求键返回409，不回显已签发凭证。原 `/bootstrap` 首次初始化行为保留。

`PUT /api/v1/members/{id}` 接受 name、role、state（ACTIVE/DISABLED/REMOVED）、revision。禁用/移除即时撤销个人凭证；重新启用不会恢复旧凭证，可由管理员调用 `POST /api/v1/members/{id}/credentials` 签发新凭证。管理员不能取得他人的OWNER凭证。移除后不能恢复此成员；先移交其负责的知识库，权限条目移除但历史审计保留。所有者不能通过此接口转移角色或被禁用/移除。旧DELETE成员接口继续表示禁用。
