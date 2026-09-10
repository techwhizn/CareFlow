"""Run one service with .env loaded as data, never shell-evaluated."""

import argparse
import os
from pathlib import Path
import subprocess
import shutil
import tempfile

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser()
parser.add_argument(
    "service", choices=["backend", "worker-api", "worker-consumer", "web"]
)
args = parser.parse_args()
env = os.environ.copy()
for line in (root / ".env").read_text().splitlines():
    if line.strip() and not line.lstrip().startswith("#") and "=" in line:
        key, value = line.split("=", 1)
        env[key] = value
if args.service == "backend":
    java = Path(env["JAVA_HOME"]) / "bin/java" if env.get("JAVA_HOME") else "java"
    runtime = Path(tempfile.mkdtemp(prefix="careflow-java-")) / "platform.jar"
    shutil.copyfile(root / "backend/target/knowledge-platform-0.1.0.jar", runtime)
    command = [str(java), "-jar", str(runtime)]
elif args.service == "web":
    command = ["npm", "run", "dev", "--prefix", "web"]
elif args.service == "worker-api":
    command = [
        "uv",
        "run",
        "--project",
        "worker",
        "uvicorn",
        "careflow.api:app",
        "--app-dir",
        "worker",
        "--host",
        "127.0.0.1",
        "--port",
        "8090",
    ]
else:
    env["PYTHONPATH"] = str(root / "worker")
    command = ["uv", "run", "--project", "worker", "python", "-m", "careflow.consumer"]
try:
    result = subprocess.call(command, cwd=root, env=env)
finally:
    if args.service == "backend":
        shutil.rmtree(runtime.parent)
raise SystemExit(result)
