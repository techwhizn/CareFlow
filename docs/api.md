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

资源授权 `PUT .../permissions` 是 **全量替换**，不是增量合并。使用 `grants: {subject_id: [read,download,edit,publish,manage]}`，并携带资源 revision。知识库创建者默认可管理；文档显式 ACL 会进一步收窄创建者权限。多主体管理表单加载现有授权并提示替换影响。

应用凭证默认 90 天到期；`DELETE /credentials/{id}` 立即撤销。轮换采用先创建新凭证、迁移调用方、再撤销旧凭证的流程。应用密钥界面可配置动作范围和有效期，详见V1-06。

## 成员 MFA

成员可通过 `GET /api/v1/mfa` 查看状态，`POST /api/v1/mfa/enroll` 生成一次性 TOTP 绑定信息，随后使用 `POST /api/v1/mfa/enable` 和 `{code}` 启用。启用后，除 MFA 管理操作外的公共请求必须携带当前 `X-MFA-Code`；`POST /api/v1/mfa/disable` 也必须提供有效验证码。密钥加密存储，响应不会返回加密值。OPS 运维只读角色和应用凭证不走成员 MFA 流程，仍受原有权限边界保护。

## 企业统一身份认证

配置 OIDC introspection 后，`POST /api/v1/sso/exchange` 接受外部令牌并返回8小时 CareFlow 成员令牌。仅已绑定外部主体的成员可交换；不自动注册、不从外部声明授予角色。IdP 必须通过 HTTPS，配置缺失或不可用时返回 `503 SSO_UNAVAILABLE`。

管理员使用 `PUT /api/v1/members/{id}/external-identity` 绑定 `{issuer, subject, revision}`；签发方必须是 HTTPS 且同一租户内唯一。绑定、变更和交换均记录审计。

## 企业系统集成

`GET/POST /api/v1/integrations` 管理租户 Webhook 端点，创建请求需 `kind=webhook`、HTTPS 地址（本地联调可用 localhost HTTP）、至少16字符 secret 和事件集合。列表只返回配置元数据，不返回 secret；`DELETE /api/v1/integrations/{id}?revision=N` 按版本停用并写入审计。事件投递器未配置时不会伪造送达结果，详细边界见[企业系统集成](integrations.md)。

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

## 多主体授权编辑（V1-05）

`GET /api/v1/knowledge-bases/{id}/authorization` 与 `GET /api/v1/documents/{id}/authorization` 返回同一事务内的 revision、当前 grants、可选成员/应用 subjects，以及文档 restricted 状态。仅当前资源管理者可查看；主体列表按企业隔离。已有GET permissions数组接口保留，文档也提供GET permissions。

PUT permissions仍为全量替换，最多500个主体，每个主体最多5个动作；显式校验空值、未知动作、已移除/跨企业主体。文档动作必须是该主体知识库动作的子集，不符返回400 DOCUMENT_PERMISSION_EXCEEDS_KB；任何校验失败都回滚整次替换，旧授权与revision不变。

UI加载现有多主体授权，修改后需核对确认。发生409保留本地内容，要求重新加载最新授权后再编辑；不会自动覆盖别人的修改。文档收窄可能撤销操作者自身管理权，保存前有明确提示。

## 应用 API Key 范围与期限（V1-06）

`POST /api/v1/applications/{id}/credentials` 可携带 `{scopes:["SEARCH","ANSWER"], expires_in_days:90}`。允许READ（受资源权限约束的GET读取）、SEARCH（搜索POST）、ANSWER（问答POST），至少选一个；期限1～365天。无请求体保留默认READ/SEARCH/ANSWER及90天，便于旧调用方迁移。历史应用密钥迁移为这三个范围，个人凭证不受此应用scope限制。

响应返回id、token、expires_at、scopes；token仅本次创建显示。`GET /applications/{id}/credentials`查看元数据，`DELETE /applications/{id}/credentials/{credential}`按应用范围撤销，开发者不必取得全企业凭证管理权。跨应用撤销拒绝；不能通过body自称用户身份。

scope先于业务操作校验，未授权操作统一404，不消耗查询额度；到期或撤销返回401。新密钥不会撤销旧密钥：先迁移调用方并验证，再显式撤销旧密钥。收窄scope不等于收窄所有知识库权限，实际访问继续取应用绑定和资源ACL交集。

## 知识库属性与概览（V1-08）

`GET /knowledge-bases` 和 `GET /knowledge-bases/{id}` 返回 tags 数组（历史数据为空数组）。创建仍接受 name、description。

`GET /knowledge-bases/{id}/settings` 仅当前知识库管理者可访问，返回 knowledge_base 和本企业启用的 OWNER/ADMIN/KNOWLEDGE_MANAGER 责任人候选。`PUT /knowledge-bases/{id}` 接受 name、description、language（例如 zh、en-US，最长20）、tags（最多20个非空标签，每个最多50字）、owner_id、revision。全部替换属性；旧 revision 返回409且不写入。标签去首尾空格并去重。责任人必须是当前启用的知识管理角色；移交立即改变隐式权限，显式ACL保留，返回 id/revision 确认而不在移交后读取资源。属性修改不切换解析或模型配置。

`GET /knowledge-bases/{id}/overview` 逐项授权后统计 document_count、effective_chunk_count（当前发布版本的启用切片）、failed_job_count（仅库管理者可见且只计有编辑权的文档）、known_source_bytes、unknown_source_objects。原文件空间按可读版本的 object_key 去重，含历史版本，不含向量索引、备份和已删除资料。V8前文件大小未知时显式计入 unknown_source_objects，不能当作零字节；新上传记录大小，复制草稿沿用相同对象与大小。

applications 返回当前绑定应用的 id/name/published；还需具备应用管理角色，其他主体返回空数组并标明 applications_visible=false。无知识库读取权限或跨企业访问统一404。概览与属性设置读取在同一企业锁下完成，避免与撤权/移交交错。

## 文档元数据与有效期（V1-11）

`GET/PUT /documents/{id}/metadata` 返回/更新 title、source、language、tags（最多20个，每个50字）、product_models（最多50个，每个100字）、valid_from、valid_until、revision。读取需read，修改需edit，均受文档收窄ACL限制。source只作为文本保存，服务不会抓取该地址。

时间必须使用含Z或UTC偏移的ISO 8601，例如 `2026-09-10T09:00:00+08:00`。统一按UTC存入DATETIME，返回UTC时间，界面展示本地时区；支持UTC年份1000～9999。空值代表不限制；区间为包含开始、不包含结束，开始必须早于结束。修改立即影响默认召回范围、证据外发复核、流式继续交付和历史答案可见性；已有版本自身有效期仍作为额外限制。原文件/切片不因此改写或自动发布。

更新使用文档共享revision；冲突409不写入元数据或历史。`GET /documents/{id}/metadata-history?page=0` 需edit，50条一页，返回操作者、时间、修订以及before/after快照（metadata_json）。旧数据没有凭空生成修改记录。历史快照按文档授权保护，普通审计日志仅保存修订号。

## 上传暂存与任务检查点（V1-14）

上传接口保持兼容。新增内部上传暂存：先用短事务登记唯一对象键（15分钟有效），再在事务外写S3，最后短事务重新认证/授权、核对幂等和重复内容，原子创建文档版本、任务及Outbox。相同请求并发最终指向同一任务；未挂接对象不作为文档可见。原凭证撤销或知识库归档后不得完成挂接。暂存过期返回409 UPLOAD_EXPIRED。

公共任务响应增加checkpoint、heartbeat_at，继续隐藏lease_token、request_key和upload_fingerprint。阶段为STARTED、SOURCE_READY、PARSED（解析）或INDEXING、INDEX_VERIFIED（索引），完成为DONE。检查点表示最近已确认阶段，不是进度百分比；失败后从当前任务阶段的安全起点重跑，不承诺恢复解析进程内存或部分模型结果。

内部Worker使用 `POST /internal/v1/jobs/{id}/checkpoint`，X-Lease-Token及 `{stage}`；旧租约/取消/已删除资料拒绝，阶段不能倒退。心跳每20秒，租约90秒。租约过期重投，总计最多3次；INVALID_FILE、PARSE_TIMEOUT、PARSE_RESOURCE_LIMIT、PARSING_FAILED、MODEL_CONFIGURATION_REQUIRED不自动重试。取消即时封锁回调，解析子进程在检测租约丢失后终止；已经发出的模型请求成本不能因此倒退。

## 知识库生命周期（V1-09）

`GET /knowledge-bases/{id}/impact` 需manage，返回当前status/revision、当前可读文档数量、本人依赖答案数量、按应用管理角色过滤的引用应用和scope_notice。计数不是整个企业的完整可见性，操作本身作用于整库，界面必须提示可能影响其他成员。

`PUT /knowledge-bases/{id}/state` 兼容原路径，要求status（ACTIVE/ARCHIVED/DELETED）及revision。归档立即停止新检索与正在运行/排队的任务，封锁旧回调；相关历史答案因证据失活隐藏。恢复仅允许ARCHIVED→ACTIVE，必须有本企业启用的知识管理责任人；保留当前ACL/发布版本/有效期，失败任务不自动复活。相同状态不重复修订，过期revision返回409。

删除在同一事务中设DELETED、取消任务并登记cleanup_requests，返回cleanup_request_id；所有公共资源访问立即404，不提供删除后恢复。这里只登记物理清理请求，执行与保留期由V1-23完成，不能把PENDING请求显示成已清理。
