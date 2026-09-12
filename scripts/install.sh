#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker 未安装，请先安装 Docker Desktop 或 Docker Engine。" >&2
  exit 1
fi
if ! docker compose version >/dev/null 2>&1; then
  echo "Docker Compose 不可用，请升级到包含 Compose v2 的 Docker。" >&2
  exit 1
fi

if [[ ! -f .env ]]; then
  python3 scripts/init-local-env.py --copy-bootstrap-token || {
    echo "环境文件已生成，但初始化密钥未能复制；可稍后运行 scripts/copy-bootstrap-token.py。" >&2
  }
else
  echo "检测到已有 .env，保留现有配置。"
fi

echo "启动 CareFlow 服务并构建本地镜像……"
docker compose up -d --build
echo
docker compose ps
echo
echo "安装完成：Web http://localhost:5173  ·  API http://localhost:8080"
echo "首次登录请使用 .env 中的 BOOTSTRAP_TOKEN；可运行 scripts/copy-bootstrap-token.py 复制。"
