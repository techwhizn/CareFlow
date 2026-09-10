# 性能测量

基准要求10万有效已发布切片、至少10个独立租户。记录真实行数、模型身份、机器和资源限制，不能以生成的文件行数替代实际索引数。PRD建议P95目标保持普通API500ms、纯检索1s、完整检索3s、问答首Token5s。

## 公共API负载

准备合成测试数据及十个所有者身份。将私有JSON数组写入仓库外或已忽略的`.local`目录，每项含`token`、`kb_id`；文件权限设为600。按顺序准备的CSV设备编号使用`CF-P01-00001`至`CF-P10-10000`，每行包含设备、处理动作和秒数。脚本针对此合成数据查询，不适合直接对业务资料运行。

```bash
# CAREFLOW_BENCHMARK_ACTORS为私有JSON路径；CAREFLOW_URL为公共Java API地址。
uv run --project worker python scripts/performance-benchmark.py \
  --output .local/performance-run-01.json --count 100 --qps 5 --concurrency 10 --answers
```

脚本只保存请求状态、租户序号和计时，不保存凭证、查询响应正文或模型文本。查询会生成正常用量和日志；`--answers`明确调用真实生成服务，可能计费，并在第一个非空答案delta后取消。它测量首个答案Token，不是完整问答成功率或答案质量。

每种操作先逐租户串行请求，再按固定5QPS到达、最多10在途请求施压。并发耗尽的到达记为LOAD_GENERATOR_CAPACITY，计入失败率，禁止静默排队后只报告实际低速成功请求。所有失败保留；P50/P95采用成功样本最近秩，必须同时展示样本数、吞吐和失败率。旧报告路径不能覆盖。

首轮称为first_query_serial，仅表示这一轮首次查询；没有清除OS/Milvus缓存或重载模型，不能表述为严格冷启动。严格冷启动需要单独记录服务重启、加载及第一批请求；与暖态分开。

## 计时范围

普通API及完整检索使用客户端墙钟时间，包含网络、Java授权、查询记录与模型调用。管理员debug响应的recall_timings_ms逐配置组报告Worker纯检索：集合检查、Milvus dense/BM25及RRF，排除查询Embedding；不包含Java或HTTP排队。重排耗时来自Java边界，包含Worker调用和模型。未报告的计时不按零统计。

负载期间停止无关构建、测试及其他模型流量，记录仍运行的服务、CPU/内存配额和网络位置。若模型服务容量不足，保留503/限流、延迟和受限吞吐，分析模型并发、批处理或硬件方案；不能改低目标或以关键词单路代替完整混合检索。
