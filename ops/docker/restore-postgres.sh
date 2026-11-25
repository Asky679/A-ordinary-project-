#!/usr/bin/env sh
set -eu

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 path/to/postgres-backup.dump" >&2
  exit 2
fi

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
cd "$ROOT"
BACKUP="$1"
if [ ! -s "$BACKUP" ]; then
  echo "Backup file does not exist or is empty: $BACKUP" >&2
  exit 2
fi
if [ "${FORCE:-0}" != "1" ]; then
  printf "This replaces current business data. Type RESTORE to continue: "
  read -r answer
  [ "$answer" = "RESTORE" ] || { echo "Cancelled."; exit 1; }
fi

docker compose stop app interview-agent >/dev/null
restore() {
  docker compose exec -T postgres sh -lc 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner --no-acl' < "$BACKUP"
}
if restore; then
  docker compose up -d app interview-agent
  echo "PostgreSQL restore complete; application services restarted."
else
  echo "Restore failed. Application services remain stopped; inspect the log before retrying." >&2
  exit 1
fi
