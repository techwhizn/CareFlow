#!/usr/bin/env bash
set -euo pipefail
umask 077
backup_dir=${1:?Usage: scripts/backup.sh /absolute/backup-directory}
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
exec python3 "$script_dir/managed_backups.py" mysql "$backup_dir"
