# 相关性门槛与校准

知识库已发布配置中的`retrieval.minimum_rerank_score`用于后续查询，不依赖重新建立索引。配置草稿不生效；回滚恢复旧查询规则。请求中的`minimum_rerank_score`只能收紧已发布门槛，两者取最大值。留空表示使用已发布配置；若两者都为空则不按分数过滤。

分数必须来自实际Rerank响应，不能把RRF、向量相似度或缺失分数当作Rerank置信度。比较采用`score >= threshold`，不同模型的分数不能直接比较。存在门槛时，评分降级或未知分数不会作为达标证据。

`evidence_status`区分AVAILABLE（有证据）、DEGRADED（允许降级且有证据）、BELOW_THRESHOLD（候选未达门槛）、SCORE_UNAVAILABLE（无法评分）、NO_MATCH（没有可用匹配）。未允许降级的模型错误返回明确服务错误。应用请求仅在应用与知识库都允许时降级。调试排除原因仍仅对有权限的管理用户开放。

## 校准工具

输入JSON数组，每项包含明确的布尔`relevant`标注和真实数值`score`；可附query_id、chunk_id用于复核。正负样本都必须存在，不允许缺失分数、字符串布尔值和非有限数。

```bash
PYTHONPATH=worker uv run --project worker python scripts/calibrate-relevance.py \
  --input labelled-scores.json --output calibration.json --minimum-precision 0.95
```

工具遍历观察到的分数门槛，输出每个门槛的混淆矩阵、精确率、召回率与F1。在满足最低精确率的点中选择F1最高者，同分时采用更高门槛。无可行点时保留曲线、selected为null并以退出码2结束。工具只生成报告，不修改或发布配置。

实际使用需要按模型、业务资料和查询分布分别标注，并分离校准集与验证集；不可把样本内最优结果当作泛化效果。审阅误收与漏召回后，管理员在配置页面明确保存并发布。更换Rerank模型或资料分布变化后需重新标定。合成验收结果见[v1-27报告](reports/v1-27-threshold.md)，其中的严格阈值不是默认值。
