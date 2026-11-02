#!/usr/bin/env python3
"""把 jobs 表中残留的英文城市翻译为中文，并同步 location/search_text。"""
from __future__ import annotations

import importlib.util
import json
import re
from collections import Counter
from pathlib import Path

import psycopg2

ROOT = Path(__file__).resolve().parent.parent
DB = dict(host="127.0.0.1", port=5432, dbname="careernav", user="careernav", password="")

spec = importlib.util.spec_from_file_location("combined_import", ROOT / "ops" / "import_combined_jobs.py")
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(module)
normalize_city = module.normalize_city


def clean(value) -> str:
    return str(value or "").strip()


def main() -> int:
    conn = psycopg2.connect(**DB)
    changes = []
    try:
        with conn, conn.cursor() as cur:
            cur.execute("""
                SELECT id, city, location, area, search_text
                FROM jobs
                WHERE city ~ '[A-Za-z]'
                ORDER BY id
            """)
            rows = cur.fetchall()
            for job_id, old_city, old_location, area, search_text in rows:
                new_city = normalize_city(old_city)
                if not new_city:
                    continue
                new_location = " ".join(part for part in (new_city, clean(area)) if part)
                text = clean(search_text)
                if clean(old_city) and clean(old_city) in text:
                    text = text.replace(clean(old_city), new_city)
                elif new_city not in text:
                    text = (text + " " + new_city).strip()
                changes.append((new_city, new_location, text, job_id, clean(old_city)))

            for new_city, new_location, text, job_id, old_city in changes:
                cur.execute("""
                    UPDATE jobs
                    SET city = %s, location = %s, search_text = %s
                    WHERE id = %s
                """, (new_city, new_location or None, text or None, job_id))

        report = {
            "rows_updated": len(changes),
            "translations": dict(Counter(old for _, _, _, _, old in changes)),
            "english_city_rows_remaining": 0,
        }
        with conn, conn.cursor() as cur:
            cur.execute("SELECT COUNT(*) FROM jobs WHERE city ~ '[A-Za-z]'")
            report["english_city_rows_remaining"] = cur.fetchone()[0]
        output = ROOT / "output" / "city_translation_report.json"
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0
    finally:
        conn.close()


if __name__ == "__main__":
    raise SystemExit(main())