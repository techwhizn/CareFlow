# V1-02 真实模型适配与连接检测

日期：2026-09-10。本地 Intel x86_64 / Docker Linux CPU 环境；所有输入均为合成数据。

真实 BAAI Embedding 与 Rerank 已通过固定 SHA 下载，权重 SHA-256 校验后离线加载；Embedding 为 512 维，向量归一化且不同文本输出不同向量；真实交叉编码器将设备故障说明排在天气文本前，超模型 Token 窗口返回 400。模型及资源配置见 [本地服务说明](../../tools/local-models/README.md)。服务鉴权、并发限制、输入限制及错误返回均由服务实现，不以固定向量冒充模型。

新增连接检测命令复用 Worker 适配器，依次运行三个模型，任何阶段失败都保留安全错误码且退出非零，不回显响应正文或凭证。[真实检测报告](v1-02-model-probe.json)：Embedding 512 维、29 输入 Token；Rerank 两条实际排名；deepseek-v4-flash 流完整结束。时间仅为单次本机观测，不是性能验收。

[完整真实链路](v1-02-real-chain.json) 通过 Java 上传、MinIO、RabbitMQ、Python 解析、实际 Embedding、Milvus dense/BM25、发布、真实 Rerank 与 DeepSeek SSE done，全程未降级。报告正文来自仓库合成产品说明。

连接检测新增10项 HTTP 测试替身用例：401/403/429/503、超时、维度错误、重复向量序号、非有限重排分数、生成缺少结束标记及安全输出。它们验证故障处理，不能替代上述真实链路。Worker 51项通过/1项显式跳过；可选模型服务5项测试通过；Ruff通过。CI已增加可选服务接口测试，尚未执行远端CI。

限制：检测读取部署环境配置；后台保存的模型档案尚未绑定知识库运行快照（V1-03）。检测不提供完整分项账单；本地模型 Token 是实际 tokenizer attention mask 数，云生成与缺失用量的结算仍由 V1-37/38补齐。没有以成功检测宣称整体产品完成。
