# V1-13 OCR 与解析资源验收

日期：2026-09-10；仅本地开发验证，全部输入为仓库合成样例。

Docker Hub 认证超时已通过可选的 Docker 官方 ECR Public 基础镜像解决。默认基础镜像不变；`docker compose build --build-arg PYTHON_IMAGE=public.ecr.aws/docker/library/python:3.12-slim worker-consumer` 构建成功。Linux 非 root 用户 10001，Tesseract eng/chi_sim，2 CPU、2 GiB、128 进程上限。

执行 [容器验证脚本](../../scripts/verify-ocr-container.py)：PNG、JPEG、纯扫描 PDF 识别 CareFlow/CF-100；中文图片识别“知识库”；所有结果携带 OCR 质量警告。页数、像素、压缩展开超限返回 INVALID_FILE；1 秒解析预算触发 PARSE_RESOURCE_LIMIT。直接调用与解析子进程相同的资源设置函数，128 MiB 地址空间拒绝 256 MiB 分配，1 秒 CPU 限制终止忙循环。PDF 默认 500 页可收紧；解析库不执行嵌入脚本。

真实 Java/MySQL → MinIO → RabbitMQ → Linux Worker → Java 回调链路导入中文 PNG 和扫描 PDF，两项任务 DONE。通过公共 API 人工编辑后修订递增，原来源警告保持；浏览器核对扫描 PDF 原文、页码、OCR 提示和修订内容。[机器报告](v1-13-ocr-real.json)。首次验收脚本错用 location_json 字段，修正为公共契约 location 后重跑通过；首次失败未计作通过。

Worker 41 项测试通过、1 项 Milvus 环境测试显式跳过；前端构建通过。Ruff 与格式检查通过。镜像构建和真实容器验收弥补主机不执行 Linux 内存上限的限制。

样例与复现命令见 [examples/ocr](../../examples/ocr/README.md)。此记录证明格式支持和资源保护，不代表真实业务扫描件识别准确率或生产性能 SLA。历史构建阻塞见 [原进度记录](v1-13-parser-limits-progress.md)。
