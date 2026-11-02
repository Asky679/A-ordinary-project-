#!/usr/bin/env python3
"""Create Java skill-assessment tables and seed the reviewed question bank."""
from __future__ import annotations

import json
from pathlib import Path

import psycopg2
from psycopg2.extras import execute_values

ROOT = Path(__file__).resolve().parent.parent.parent
SKILLS = ROOT / "ops" / "skills"
DB = dict(host="127.0.0.1", port=5432, dbname="careernav", user="careernav", password="")


def main() -> int:
    payload = json.loads((SKILLS / "java_questions.json").read_text(encoding="utf-8"))
    schema = (SKILLS / "schema.sql").read_text(encoding="utf-8")
    rows = []
    for q in payload["questions"]:
        source = q["source"]
        rows.append((
            q["external_id"], q["skill"], q["level"], q["topic"], q["question_text"],
            json.dumps(q["options"], ensure_ascii=False), q["correct_option"], q["explanation"],
            source["repo"], source["file"], source["url"], source["commit"], source["license"],
        ))
    conn = psycopg2.connect(**DB)
    try:
        with conn, conn.cursor() as cur:
            cur.execute(schema)
            execute_values(cur, """
                INSERT INTO skill_questions (
                    external_id, skill, level, topic, question_text, options_json,
                    correct_option, explanation, source_repo, source_file, source_url,
                    source_commit, license, verified, active
                ) VALUES %s
                ON CONFLICT (external_id) DO UPDATE SET
                    skill=EXCLUDED.skill, level=EXCLUDED.level, topic=EXCLUDED.topic,
                    question_text=EXCLUDED.question_text, options_json=EXCLUDED.options_json,
                    correct_option=EXCLUDED.correct_option, explanation=EXCLUDED.explanation,
                    source_repo=EXCLUDED.source_repo, source_file=EXCLUDED.source_file,
                    source_url=EXCLUDED.source_url, source_commit=EXCLUDED.source_commit,
                    license=EXCLUDED.license, verified=EXCLUDED.verified, active=EXCLUDED.active
            """, [r + (True, True) for r in rows], page_size=50)
            cur.execute("SELECT skill, level, count(*) FROM skill_questions WHERE active GROUP BY skill, level ORDER BY skill, level")
            counts = cur.fetchall()
        print(json.dumps({"inserted_or_updated": len(rows), "counts": counts,
                          "source": payload["source_repo"], "commit": payload["source_commit"],
                          "license": payload["license"]}, ensure_ascii=False, indent=2))
        return 0
    finally:
        conn.close()


if __name__ == "__main__":
    raise SystemExit(main())