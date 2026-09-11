#!/usr/bin/env bash
set -euo pipefail
umask 077
: "${CAREFLOW_BACKUP_ROOT:?Set CAREFLOW_BACKUP_ROOT to an absolute backup directory}"
retention_days=${CAREFLOW_BACKUP_RETENTION_DAYS:-30}
case "$retention_days" in
  ''|*[!0-9]*) echo "CAREFLOW_BACKUP_RETENTION_DAYS must be numeric" >&2; exit 2 ;;
esac
exec python3 "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/managed_backups.py" \
  mysql "$CAREFLOW_BACKUP_ROOT" --retention-days "$retention_days"
