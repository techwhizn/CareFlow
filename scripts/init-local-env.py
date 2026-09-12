"""Generate local infrastructure secrets without printing them or overwriting user settings."""

import base64
import argparse
from pathlib import Path
import secrets
import shutil
import subprocess


def copy_bootstrap_token(env_text: str) -> bool:
    token = next(
        (line.split("=", 1)[1] for line in env_text.splitlines() if line.startswith("BOOTSTRAP_TOKEN=")),
        "",
    )
    if not token:
        return False
    command = shutil.which("pbcopy") or shutil.which("wl-copy") or shutil.which("xclip")
    if not command:
        return False
    args = [command]
    if command.endswith("xclip"):
        args.extend(["-selection", "clipboard"])
    subprocess.run(args, input=token.encode(), check=True)
    return True


parser = argparse.ArgumentParser(description="生成本地部署密钥")
parser.add_argument(
    "--copy-bootstrap-token",
    action="store_true",
    help="生成后复制初始化密钥到系统剪贴板（不在终端显示）",
)
args = parser.parse_args()

path = Path(".env")
if path.exists():
    raise SystemExit(".env already exists; nothing overwritten")
text = Path(".env.example").read_text()
for key in [
    "DATABASE_PASSWORD",
    "MYSQL_ROOT_PASSWORD",
    "BOOTSTRAP_TOKEN",
    "DEFAULT_ACCESS_TOKEN",
    "INTERNAL_TOKEN",
    "RABBITMQ_PASSWORD",
    "S3_SECRET_KEY",
]:
    text = text.replace(key + "=\n", key + "=" + secrets.token_urlsafe(36) + "\n")
text = text.replace(
    "MODEL_CONFIG_ENCRYPTION_KEY=\n",
    "MODEL_CONFIG_ENCRYPTION_KEY="
    + base64.b64encode(secrets.token_bytes(32)).decode()
    + "\n",
)
path.write_text(text)
path.chmod(0o600)
copied = copy_bootstrap_token(text) if args.copy_bootstrap_token else False
print("Created .env with local secrets. Configure real model endpoints before indexing.")
if args.copy_bootstrap_token:
    print("Bootstrap token copied to the system clipboard." if copied else "Clipboard tool unavailable; use scripts/copy-bootstrap-token.py.")
