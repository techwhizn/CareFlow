# 元数据检索过滤

搜索与问答请求可附 `filters`，最多10个条件，条件之间为AND。省略或空数组保留原行为。每个条件包含 `field`、`operator`、`value`，不能传入SQL、Milvus表达式、租户字段或权限主体。

| 字段 | 操作符 | 值 |
| --- | --- | --- |
| title / source / language | eq / in / contains | eq与contains为非空字符串；in为1～20个字符串 |
| tags / product_models | contains / in | contains为完整标签或型号；in表示列表中至少一个完整值匹配 |
| valid_from / valid_until | eq / gte / lte | 带时区ISO8601时间；转换为UTC瞬时比较 |

字符串最多2000字符，大小写敏感。标题/来源等contains表示字面子串，不解释百分号、下划线、正则或引号。标签和型号不做子串匹配。未设置的时间不匹配时间条件；默认有效期检查仍然执行，客户端不能用过滤取回过期资料。

```json
{"query":"CF-100如何复位","mode":"keyword","limit":6,
 "filters":[{"field":"product_models","operator":"contains","value":"CF-100"},
            {"field":"language","operator":"eq","value":"zh"}]}
```

Java在授权、发布与有效期范围内应用类型化谓词，将最终版本ID白名单传给召回；过滤在召回前生效，不会让无权限候选进入模型。未知字段、操作符、空值、错误类型和无时区时间在额度预占与模型调用前返回400。条件不会改变授权规则，已选证据后续仍按既有边界重新鉴权。

后台搜索与问答支持添加/移除条件，多个in值用英文逗号分隔；API可直接发送字符串数组（例如值本身含逗号）。Python提供MetadataFilter并通过Query.filters传入，Java提供MetadataFilter/MetadataField/MetadataOperator记录与枚举。

当前在Java读取已发布文档元数据后求值，避免不同数据库JSON表达式差异；这不是针对10万切片规模完成的性能优化，规模验证仍见V1-45。完整真实混合召回过滤验收依赖V1-24，不以空范围请求代替。
