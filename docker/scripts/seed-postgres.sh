#!/bin/sh
set -eu

echo "Waiting for PostgreSQL..."
until pg_isready >/dev/null 2>&1; do
  sleep 2
done

DUMP="/seed/postgres/careernav.dump"
JOB_COUNT="$(psql -tAc "SELECT CASE WHEN to_regclass('public.jobs') IS NULL THEN 0 ELSE (SELECT count(*) FROM public.jobs) END")"
JOB_COUNT="$(printf '%s' "$JOB_COUNT" | tr -d '[:space:]')"

if [ "${JOB_COUNT:-0}" -gt 0 ]; then
  echo "PostgreSQL already contains ${JOB_COUNT} jobs; seed skipped."
  exit 0
fi

if [ ! -s "$DUMP" ]; then
  echo "No seed dump found at $DUMP. Starting with an empty database."
  exit 0
fi

echo "Restoring PostgreSQL seed from $DUMP..."
pg_restore --clean --if-exists --no-owner --no-acl --dbname="$PGDATABASE" "$DUMP"
echo "PostgreSQL seed restored."
