# 合成OCR验收资料

本目录仅含程序生成的合成文本图片与扫描PDF，不包含客户文件或真实个人数据。

英文内容为“CareFlow synthetic OCR fixture / Device CF-100 restart instructions”；中文为“知识库测试 产品型号 CF-100 重启设备”。PNG与JPEG验证图片输入，PDF只有栅格图片、无文本层。生成使用本机Arial/STHeiti渲染，未嵌入或分发字体文件。

在项目根目录构建Worker后执行：

```bash
docker compose build worker-consumer
docker run --rm --cpus=2 --memory=2g --pids-limit=128 \
  --cap-drop=ALL --security-opt=no-new-privileges \
  -v "$PWD/examples/ocr:/fixtures:ro" \
  -v "$PWD/scripts/verify-ocr-container.py:/app/verify_ocr.py:ro" \
  careflow-worker-consumer python /app/verify_ocr.py /fixtures
```

测试验证识别结果、来源警告、非root与中英语言包，以及页数、像素、解压、超时与内存限制。它不验证复杂真实扫描质量或商业性能。Docker Hub不可达时，可向构建命令增加`--build-arg PYTHON_IMAGE=public.ecr.aws/docker/library/python:3.12-slim`，使用Docker官方ECR公共副本。
