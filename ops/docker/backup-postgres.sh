#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
cd "$ROOT"
mkdir -p backups
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
TARGET="backups/postgres-$STAMP.dump"

echo "Creating PostgreSQL backup: $TARGET"
docker compose exec -T postgres sh -lc 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$TARGET"
echo "Backup complete: $TARGET"
