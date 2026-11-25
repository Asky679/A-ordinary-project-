from __future__ import annotations

import json
import logging
import threading
import time
from typing import Any

import psycopg

from .config import settings

logger = logging.getLogger(__name__)
_LOCK = threading.RLock()
_SCHEMA_READY = False
_DB_DISABLED_UNTIL = 0.0
_RETRY_SECONDS = 30.0


def _available() -> bool:
    return settings.state_persistence and bool(settings.database_url) and time.time() >= _DB_DISABLED_UNTIL


def _disable(error: Exception) -> None:
    global _DB_DISABLED_UNTIL
    _DB_DISABLED_UNTIL = time.time() + _RETRY_SECONDS
    logger.warning("LangGraph state persistence unavailable; using memory cache: %s", error)


def _connect():
    return psycopg.connect(
        settings.database_url,
        autocommit=True,
        prepare_threshold=0,
        connect_timeout=3,
    )


def ensure_schema() -> bool:
    global _SCHEMA_READY
    if not _available():
        return False
    with _LOCK:
        if _SCHEMA_READY:
            return True
        try:
            with _connect() as conn:
                conn.execute("""
                    CREATE TABLE IF NOT EXISTS langgraph_interview_states (
                        session_id text PRIMARY KEY,
                        state_json jsonb NOT NULL,
                        updated_at timestamptz NOT NULL DEFAULT now()
                    )
                """)
            _SCHEMA_READY = True
            return True
        except Exception as exc:  # noqa: BLE001
            _disable(exc)
            return False


def save_context(session_id: str, context: dict[str, Any]) -> bool:
    if not session_id or not ensure_schema():
        return False
    payload = json.dumps(context, ensure_ascii=False, separators=(",", ":"))
    try:
        with _connect() as conn:
            conn.execute("""
                INSERT INTO langgraph_interview_states(session_id, state_json, updated_at)
                VALUES (%s, %s::jsonb, now())
                ON CONFLICT (session_id) DO UPDATE
                   SET state_json = EXCLUDED.state_json,
                       updated_at = now()
            """, (str(session_id), payload))
        return True
    except Exception as exc:  # noqa: BLE001
        _disable(exc)
        return False


def load_context(session_id: str) -> dict[str, Any] | None:
    if not session_id or not ensure_schema():
        return None
    try:
        with _connect() as conn:
            row = conn.execute(
                "SELECT state_json::text FROM langgraph_interview_states WHERE session_id = %s",
                (str(session_id),),
            ).fetchone()
        if not row:
            return None
        value = json.loads(row[0])
        return value if isinstance(value, dict) else None
    except Exception as exc:  # noqa: BLE001
        _disable(exc)
        return None


def delete_context(session_id: str) -> None:
    if not session_id or not ensure_schema():
        return
    try:
        with _connect() as conn:
            conn.execute("DELETE FROM langgraph_interview_states WHERE session_id = %s", (str(session_id),))
    except Exception as exc:  # noqa: BLE001
        _disable(exc)
