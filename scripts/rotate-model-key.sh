#!/usr/bin/env bash
set -euo pipefail
umask 077
: "${CAREFLOW_API_URL:?Set CAREFLOW_API_URL, for example http://127.0.0.1:8080}"
: "${INTERNAL_TOKEN:?Set INTERNAL_TOKEN from the deployment secret store}"
: "${MODEL_CONFIG_ENCRYPTION_KEY_OLD:?Set the previous key for this maintenance window}"
: "${MODEL_CONFIG_ENCRYPTION_KEY:?Set the new key for this maintenance window}"
if [[ "$MODEL_CONFIG_ENCRYPTION_KEY_OLD" == "$MODEL_CONFIG_ENCRYPTION_KEY" ]]; then
  echo "old and new encryption keys must differ" >&2
  exit 2
fi
curl --fail --silent --show-error --max-time 30 \
  -H "X-Internal-Token: $INTERNAL_TOKEN" \
  -H 'Content-Type: application/json' \
  -X POST "$CAREFLOW_API_URL/internal/v1/maintenance/model-key-rotation"
printf '\n'
