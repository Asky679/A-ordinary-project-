#!/usr/bin/env python3
import json
import os
from datetime import date, datetime, time
from decimal import Decimal
from pathlib import Path

import psycopg2
from psycopg2 import sql
from psycopg2.extras import RealDictCursor

OUTPUT = Path(__file__).resolve().parents[1] / "output" / "database-viewer" / "data.json"
TABLES = ["jobs", "resumes", "diagnosis_reports", "interview_sessions", "job_source_policies", "job_collection_runs", "job_market_snapshots"]


def json_value(value):
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, Decimal):
        return float(value)
    if isinstance(value, (datetime, date, time)):
        return value.isoformat()
    if isinstance(value, bytes):
        return f"<binary {len(value)} bytes>"
    if isinstance(value, memoryview):
        return f"<binary {len(value)} bytes>"
    if isinstance(value, (list, tuple)):
        return [json_value(item) for item in value]
    if isinstance(value, dict):
        return {str(key): json_value(item) for key, item in value.items()}
    return str(value)


def main():
    connection = psycopg2.connect(
        host=os.getenv("DB_HOST", "127.0.0.1"),
        port=int(os.getenv("DB_PORT", "5432")),
        dbname=os.getenv("DB_NAME", "careernav"),
        user=os.getenv("DB_USER", "careernav"),
        password=os.getenv("DB_PASSWORD", ""),
    )
    result = {"generated_at": datetime.now().isoformat(), "database": "careernav", "tables": []}
    try:
        with connection, connection.cursor(cursor_factory=RealDictCursor) as cursor:
            for table in TABLES:
                cursor.execute(
                    "SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name=%s ORDER BY ordinal_position",
                    (table,),
                )
                columns = [row["column_name"] for row in cursor.fetchall()]
                if not columns:
                    continue
                select_columns = []
                for column in columns:
                    if column == "embedding":
                        select_columns.append(sql.SQL("(embedding IS NOT NULL) AS embedding_present"))
                    else:
                        select_columns.append(sql.Identifier(column))
                query = sql.SQL("SELECT {} FROM {} ORDER BY 1 DESC").format(
                    sql.SQL(", ").join(select_columns), sql.Identifier(table)
                )
                cursor.execute(query)
                rows = [{key: json_value(value) for key, value in row.items()} for row in cursor.fetchall()]
                display_columns = [column for column in columns if column != "embedding"] + (["embedding_present"] if "embedding" in columns else [])
                result["tables"].append({"name": table, "row_count": len(rows), "columns": display_columns, "rows": rows})
    finally:
        connection.close()
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(json.dumps({"output": str(OUTPUT), "tables": [{t["name"]: t["row_count"]} for t in result["tables"]], "bytes": OUTPUT.stat().st_size}, ensure_ascii=False))


if __name__ == "__main__":
    main()