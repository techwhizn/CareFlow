# 可选本地模型服务

用于在本机完成真实Embedding/Rerank联调；不属于默认生产部署，不调用付费推理服务。默认CPU、单请求推理并发，DeepSeek继续由现有Worker配置用于生成。当前锁文件面向Linux容器，Intel macOS不使用旧版PyTorch降级安装。

模型来自BAAI官方仓库，固定不可变修订：

- [bge-small-zh-v1.5](https://huggingface.co/BAAI/bge-small-zh-v1.5)：7999e1d3359715c523056ef9478215996d62a620；CLS向量L2归一化，不加查询指令，实际维度从模型配置读取。
- [bge-reranker-base](https://huggingface.co/BAAI/bge-reranker-base)：2cfc18c9415c912f9d8155881c133215df768a70；交叉编码器，logit经sigmoid转为0～1分数，尚未按业务测试集标定阈值。

上游模型卡标注MIT许可。权重和下载的上游README只保存在被Git忽略的`.local/models`；本仓库不重新分发权重。CareFlow代码采用Apache-2.0；上游模型保持各自MIT许可。

## 构建与下载

在仓库根目录：

```bash
docker build -t careflow-local-models:dev tools/local-models
mkdir -p .local/models
docker run --rm --user "$(id -u):$(id -g)" \
  -e HF_HUB_OFFLINE=0 -e HF_HOME=/tmp/hf \
  -v "$PWD/.local/models:/models" careflow-local-models:dev python download.py
```

下载命令是显式联网步骤，仅从上述固定模型仓库下载配置、tokenizer和safetensors。推理进程不下载、不加载远端自定义代码，不加载pickle权重。下载及每次启动均流式校验 safetensors 的固定 SHA-256；缺失或不匹配时启动失败。下载完成前服务不能成功启动。

Docker Hub不可达时可使用Docker官方镜像在AWS ECR Public的副本：

```bash
docker build --build-arg PYTHON_IMAGE=public.ecr.aws/docker/library/python:3.12-slim \
  -t careflow-local-models:dev tools/local-models
```

来源说明：[Docker官方发布](https://www.docker.com/press-release/docker-official-images-available-amazon-elastic-container-registry/)。该命令只在本地构建，不向仓库推送镜像。

## 启动与接入

生成独立服务令牌到本机文件，不打印到终端：

```bash
python3 - <<'PY'
from pathlib import Path
import os, secrets
p=Path('.local/model-server.env')
if not p.exists():
    fd=os.open(p,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(fd,'w') as f:
        f.write('LOCAL_MODELS_TOKEN='+secrets.token_urlsafe(48)+'\n')
PY
docker run -d --name careflow-local-models --cpus=2 --memory=4g --pids-limit=128 \
  --security-opt=no-new-privileges --cap-drop=ALL \
  --env-file .local/model-server.env -p 127.0.0.1:8092:8092 \
  -v "$PWD/.local/models:/models:ro" careflow-local-models:dev
```

将`.env`中的Embedding/Rerank地址改为`http://localhost:8092/v1`、模型名设为上面的BAAI名称，两个API_KEY填入独立本地服务令牌。Embedding revision使用固定SHA，dimensions必须与认证后的`GET /health`一致。重启Worker API与消费者。容器内Worker需要改为可达主机名，不能使用指向自身的localhost；默认运行示例是主机Worker。

接口：`POST /v1/embeddings`、`POST /v1/rerank`、`GET /health`，都要求Bearer令牌。输入最大32个Embedding文本、40个重排候选；按模型tokenizer限制512 Token（重排包含问题与候选），超限400，禁止静默截断。忙时429、执行失败503，不返回伪造向量或分数。现有600 Token切片可能超过模型自身窗口，超限需缩短/重新切片，不能将600与512视为同一tokenizer口径。

`LOCAL_RERANK_BATCH_SIZE`允许在1～40之间调整重排批次，默认4，非法值启动失败。增大批次可能提高短文本吞吐，也增加内存；应在实际长度、并发和容器限制下测量，不改变512 Token上限或静默截断。固定模型的40条短文本CPU微测中，批次4/16/40的分数与实际Token数一致，批次40平均约快11%，但不能推断长文本内存或5QPS容量。[十万分块实测](../../docs/reports/v1-45-performance.md)已表明当前2核单请求模型服务无法满足5QPS混合检索与问答；本服务用于开发验证，生产需另行配置和验证模型容量。

用量返回真实attention mask Token数，包含特殊token；这是本地推理输入量，不是云供应商账单。CPU延迟与资源上限需实际验证，不能视为生产性能承诺。

现提供认证后的 `POST /v1/tokenize`：请求与Embedding一致，返回固定模型修订、每条完整输入的原生Token数及512窗口，包含特殊Token且不截断。知识库切片配置选择 `model_tokenizer=provider`，可在索引前自动细分长文，详见[切片契约](../../docs/chunking.md)。重排窗口包含问题与候选，仍独立校验。

接口单元测试使用明确的FixtureEngine，运行`PYTHONPATH=tools/local-models uv run --project worker pytest tools/local-models/tests -q`；这些测试不代表模型已下载或真实推理通过。

## 依赖升级验证

当前固定Transformers5.17.0、FastAPI0.141.1，实际传递依赖见uv.lock。升级后用相同本地固定权重完成Tokenizer、Embedding与Rerank对照；两条中英文合成输入的计数、向量和分数一致，见[真实记录](../../docs/reports/v1-50-model-migration-real.json)。这不是完整质量评估。

本工具锁文件限定Linux。其他操作系统审计时必须显式评估Linux标记，不能接受“0个包”作为通过；CI在Linux导出并检查覆盖。`torch` CPU构建不在PyPI版本索引中，审计脚本显式查询其对应上游版本的OSV公告，保留这个覆盖限制；其他跳过包或空报告都会失败。详见[依赖记录](../../docs/reports/v1-50-model-dependencies.json)。
