#!/usr/bin/env bash
set -euo pipefail
umask 077
backup_dir=${1:?Usage: scripts/backup.sh /absolute/backup-directory}
mkdir -p "$backup_dir"
docker compose exec -T mysql sh -c 'exec mysqldump -u root -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers careflow' > "$backup_dir/mysql.sql"
# Stop writes before storage snapshot. This helper intentionally does NOT claim a live atomic cross-store backup.
printf '%s\n' 'MySQL logical snapshot created. Follow docs/operations.md to snapshot S3 and preserve deletion/revocation records before marking backup complete.'
