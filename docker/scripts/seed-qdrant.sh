#!/bin/sh
set -eu

echo "Waiting for Qdrant..."
until curl -fsS "$QDRANT_URL/healthz" >/dev/null 2>&1; do
  sleep 2
done

SNAPSHOT_DIR="/seed/qdrant"

latest_snapshot() {
  pattern="$1"
  latest=""
  for candidate in "$SNAPSHOT_DIR"/$pattern; do
    if [ -f "$candidate" ]; then
      latest="$candidate"
    fi
  done
  printf '%s' "$latest"
}

restore_collection() {
  collection="$1"
  snapshot="$2"
  if curl -fsS "$QDRANT_URL/collections/$collection" >/dev/null 2>&1; then
    echo "Qdrant collection $collection already exists; seed skipped."
    return
  fi
  if [ -z "$snapshot" ]; then
    echo "No snapshot found for $collection."
    return
  fi
  echo "Restoring Qdrant collection $collection from $(basename "$snapshot")..."
  payload="{\"location\":\"file:///seed/qdrant/$(basename "$snapshot")\"}"
  curl -fsS -X POST "$QDRANT_URL/collections/$collection/snapshots/recover" \
    -H "Content-Type: application/json" \
    --data "$payload" >/dev/null
  echo "Qdrant collection $collection restored."
}

restore_collection "$JOB_COLLECTION" "$(latest_snapshot 'careernav_jobs-*.snapshot')"
restore_collection "$INTERVIEW_COLLECTION" "$(latest_snapshot 'careernav_interview_questions-*.snapshot')"
