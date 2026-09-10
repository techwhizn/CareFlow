# 测试集、人工评审与可重复评估

## 测试集管理

“测试集与评审”页面按知识库管理题目。编辑权限沿用知识库；应用凭证和运维角色不能读取测试集。创建测试集后可导入 JSON 题目数组，逐题编辑，再保存新版本。每次保存都冻结完整题目；旧版本和评审不变，新版本重新评审。

每题记录 id、question、reference_answer、category（DIRECT/PARAPHRASE/IDENTIFIER/UNANSWERABLE/VERSION）、answerability（ANSWERABLE/UNANSWERABLE/CONFLICT）、expected_chunk_ids、test_subject_kind（MEMBER/APP）和 test_subject_id。凭证不进入测试集。题目ID唯一，最多500题、每题最多30个正确证据。参考证据必须属于对应知识库并可访问；资料撤权后版本读取重新校验，物理删除资料会清除包含其引用的测试集版本及评审副本。

逐题人工核对后提交通过或需修订，记录当前评审人、时间和意见。已有评审不能原地改写；纠正标注应生成新版。有答案或冲突题没有正确证据不能通过。后台不会将导入的候选标签、组件测试或模型自评分数自动记为人工评审。

API：`/api/v1/evaluation-datasets` GET/POST；`/{id}` GET；`/{id}/versions` POST（revision、cases）；`/{id}/versions/{version}` GET；`/{id}/versions/{version}/reviews/{caseId}` POST（decision、note）。

## 120题候选试点

[候选集](../examples/evaluation/pilot/candidate.json)包含50题直接问答、20题同义口语、20题型号编号、15题无答案和15题版本差异。60份源文件全部是虚构产品条款。当前状态是 **待人工评审**，不是已通过的人工基线。逐题核对稿见[REVIEW.md](../examples/evaluation/pilot/REVIEW.md)。权限隔离单独计量。

导入工具会创建合成知识库、复制指定知识库已发布的处理配置、解析/索引/发布合成源文档并绑定实际证据ID；不会发布软件包或项目。状态文件支持中断后继续。运行前确认目标为测试环境：

```bash
# CAREFLOW_TOKEN 通过环境传入，不放命令参数或仓库文件
uv run --project worker python scripts/prepare-evaluation-pilot.py \
  --template-kb <配置模板知识库UUID> --state .local/pilot-state.json
# 核对计划后，同一命令增加 --apply 执行知识资料导入
```

该工具不会提交人工通过记录。人工评审需在页面逐题进行；不应以“生成了120题”替代“120题已核对”。

## 运行与对比

```bash
PYTHONPATH=worker:sdk/python uv run --project worker python -m careflow.evaluation \
  --dataset-id <测试集UUID> --version-id <版本UUID> \
  --label hybrid-a --mode hybrid --output .local/eval-a.json
# 增加 --answers 会额外执行每题真实流式问答并保存回答正文，可能产生模型费用。
PYTHONPATH=worker uv run --project worker python scripts/evaluation-report.py \
  compare .local/eval-a.json .local/eval-b.json --output .local/comparison.json
```

每完成一题，运行器原子保存一次断点。中断后以相同参数增加 `--resume` 继续；数据、模式或问答阶段变化时拒绝恢复。已记录的失败保留，不自动重试或覆盖；复测另存新报告。中间文件 `complete=false`，不能当作完整实验。

必须使用题目声明身份的真实凭证；身份不匹配立即失败，不由管理员模拟另一身份。当前运行器一次使用一个身份，多身份测试集应按身份分组运行。所有查询和问答走公共API。导入状态与报告中的UUID不授予权限。

报告 schema_version=2，独立记录授权重排后、相关性门槛和最终证据限额之前的 Top10 与最终最多6条证据；分别计算 Recall/Hit/MRR。没有调试权限时Top10为未知，不用最终6条冒充。保留排除原因、模型与配置版本、发布资料版本、分阶段用量、搜索/回答P50/P95、失败率、实际费用。价格或用量缺失为未知；不同币种分别汇总，不隐含兑换。

报告的题目语义摘要包含问题、参考答案、分类和测试身份。比较要求相同题目语义、ID集合及执行阶段；证据映射可随切片版本改变，但映射摘要单独记录，必须重新核对证据正确性。不同模型或策略需记录实际配置ID与实验标签；不要在一次运行中修改已发布配置。对于切片变更，应保存原实验报告，再绑定新版正确切片运行，不回写旧报告。

页面可读取两份本地报告，比较指标及逐题失败；报告只在浏览器中读取，不上传服务器。它们是可审查的实验产物，不是服务器签名证明。

## 回答人工评分

回答生成实验与题目参考标注的评审是两件事。页面可对报告A逐题填写正确性、证据支持、引用准确、是否拒答及评审人；导出独立评分文件，然后：

```bash
PYTHONPATH=worker uv run --project worker python scripts/evaluation-report.py \
  review .local/eval-a.json .local/human-evaluation-reviews.json \
  --output .local/eval-a-reviewed.json
```

只对明确完成且人工阅读的回答评分；失败/未完成回答计入失败率，不伪造正确性。无答案拒答率与有答案误拒答率按对应已评审题目分别计算，报告保留评审覆盖数。未评审时质量分数为null。模型输出不能作为它自己唯一的裁判。

报告可包含完整问题与回答，默认存放 `.local`。只允许合成数据报告经检查后进入仓库。日志保留策略不删除本地导出的报告，导出方负责管理其生命周期。
