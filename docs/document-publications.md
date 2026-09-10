# 文档草稿与发布隔离

文件上传产生新的 document_version，不改变 documents.published_version。解析完成为PARSED，真实索引完成为READY；READY仍不表示发布。已发布过的版本禁止原地编辑、重新索引，复制草稿后再修改。源版本必须结束处理且存在切片才允许复制，防止解析中复制出半成品或空草稿。

发布使用 `POST /api/v1/documents/{id}/publications`：

```json
{"version_id":"目标版本UUID","revision":3,"version_revision":2}
```

`revision` 是核对时的文档修订，`version_revision` 是核对时的目标内容版本修订。两者都必须匹配，目标必须READY且属于同一文档，并重新检查发布权限。内容修改后即便已重新索引，旧预览请求仍返回409；不自动补最新修订号继续发布。版本修订字段必填，缺失返回400，Java/Python预览SDK调用签名同步改变，见SDK文档。

发布记录保存文档修订、内容修订、上次发布版本、操作者和时间。`GET /api/v1/documents/{id}/publications` 与文档页展示最近100条授权记录。V14之前的历史没有修订快照，显示“旧记录未采集”，不编造旧修订。回滚选择旧READY版本并使用当前文档修订再发布；回滚不改权限。

V14仅新增历史快照列与索引，保留旧数据。回退时保留新增列，不能删改已应用迁移。升级需要同时更新前端与SDK调用方；旧客户端缺少version_revision的请求会失败，不能把该变化隐藏为完全兼容。
