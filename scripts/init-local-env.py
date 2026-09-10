"""Generate local infrastructure secrets without printing them or overwriting user settings."""

import base64
from pathlib import Path
import secrets

path = Path(".env")
if path.exists():
    raise SystemExit(".env already exists; nothing overwritten")
text = Path(".env.example").read_text()
for key in [
    "DATABASE_PASSWORD",
    "MYSQL_ROOT_PASSWORD",
    "BOOTSTRAP_TOKEN",
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
print(
    "Created .env with local secrets. Configure real model endpoints before indexing."
)
