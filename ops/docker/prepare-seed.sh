#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
ARCHIVE="${1:-}"
if [ -z "$ARCHIVE" ]; then
  echo "Usage: $0 path/to/careernav-database-package.zip" >&2
  exit 2
fi
if [ ! -s "$ARCHIVE" ]; then
  echo "Seed archive not found: $ARCHIVE" >&2
  exit 2
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT INT TERM
unzip -q "$ARCHIVE" -d "$TMP"
mkdir -p "$ROOT/docker/seed/postgres" "$ROOT/docker/seed/qdrant"
find "$ROOT/docker/seed/postgres" -mindepth 1 -maxdepth 1 -type f -delete
find "$ROOT/docker/seed/qdrant" -mindepth 1 -maxdepth 1 -type f -delete
cp "$TMP"/postgres/careernav.dump "$ROOT/docker/seed/postgres/"
cp "$TMP"/qdrant/*.snapshot "$ROOT/docker/seed/qdrant/"
echo "Seed files staged under $ROOT/docker/seed"
