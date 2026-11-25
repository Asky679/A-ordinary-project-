#!/usr/bin/env python3
"""Rescore historical junior interviews with the current junior rubric.

The original overall score is kept in legacy_overall_score. The migration is
dry-run by default; pass --apply to update PostgreSQL.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "interview-agent"))
from app.junior_reference import score_junior_answer  # noqa: E402

OLD_PLANS = {
    "综合面": ["求职动机", "项目职责", "业务链路", "技术实现", "故障与取舍", "团队协作", "失败与学习", "候选人反问"],
    "技术面": ["个人背景", "项目职责", "业务链路", "技术实现", "故障与边界", "方案取舍", "结果验证", "候选人反问"],
    "HR面": ["个人背景", "求职动机", "项目概述", "团队协作", "冲突处理", "岗位意愿", "职业规划", "候选人反问"],
    "主管面": ["个人背景", "项目职责", "业务链路", "技术判断", "故障与取舍", "团队协作", "结果验证", "候选人反问"],
    "行为面": ["个人背景", "团队协作", "冲突处理", "失败与学习", "推动改变", "压力与优先级", "职业规划", "候选人反问"],
}
VERSION = "JUNIOR_ADAPTIVE_2026_11_RESCORED"


def parse_ids(value: str) -> list[int]:
    ids: list[int] = []
    for part in value.split(","):
        part = part.strip()
        if not part:
            continue
        if "-" in part:
            start, end = part.split("-", 1)
            ids.extend(range(int(start), int(end) + 1))
        else:
            ids.append(int(part))
    return sorted(set(ids))


def psql(sql: str, *, output: bool = True) -> str:
    env = dict(os.environ)
    env.setdefault("PGPASSWORD", "careernav")
    result = subprocess.run(
        [r"D:\pgsql18\pgsql\bin\psql.exe", "-h", "127.0.0.1", "-p", "5432",
         "-U", "careernav", "-d", "careernav", "-v", "ON_ERROR_STOP=1", "-Atc", sql],
        env=env, capture_output=True, encoding="utf-8", check=True,
    )
    return result.stdout.strip() if output else ""


def load_sessions(ids: list[int]) -> list[dict]:
    id_list = ",".join(str(value) for value in ids)
    sql = f"""
    SELECT json_agg(json_build_object(
      'id', s.id,
      'mode', COALESCE(s.interview_mode, ''),
      'difficulty', s.difficulty,
      'messages', s.messages_json::jsonb,
      'jobTitle', j.title,
      'jobSkills', COALESCE(j.skills_json::jsonb, '[]'::jsonb),
      'projectEvidence', COALESCE(s.project_evidence_json::jsonb, '{{}}'::jsonb),
      'overallScore', s.overall_score
    ) ORDER BY s.id)
    FROM interview_sessions s
    LEFT JOIN jobs j ON j.id = s.job_id
    WHERE s.id IN ({id_list});
    """
    raw = psql(sql)
    return json.loads(raw) if raw else []


def rescore(session: dict) -> tuple[int, list[dict]]:
    messages = list(session.get("messages") or [])
    plan = OLD_PLANS.get(str(session.get("mode") or "技术面"), OLD_PLANS["技术面"])
    job_skills = session.get("jobSkills") or []
    evidence = session.get("projectEvidence") or {}
    scores: list[int] = []
    answer_index = 0
    updated: list[dict] = []
    for message in messages:
        current = dict(message)
        if current.get("role") == "user":
            stage = plan[min(answer_index, len(plan) - 1)]
            context = {"project_evidence": evidence, "job": {"title": session.get("jobTitle") or "", "skills": job_skills}}
            result = score_junior_answer(current.get("content") or "", stage, job_skills, context)
            current["score"] = result["score"]
            scores.append(result["score"])
            answer_index += 1
        updated.append(current)
    overall = round(sum(scores) / len(scores)) if scores else 0
    return overall, updated


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--session-ids", default="98-107")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    sessions = load_sessions(parse_ids(args.session_ids))
    if not sessions:
        print("No matching sessions.")
        return 1

    statements: list[str] = ["BEGIN;"]
    print("id\told\tnew\tanswers")
    for session in sessions:
        new_score, messages = rescore(session)
        old_score = int(session.get("overallScore") or 0)
        answers = sum(1 for message in messages if message.get("role") == "user")
        print(f"{session['id']}\t{old_score}\t{new_score}\t{answers}")
        if args.apply:
            payload = json.dumps(messages, ensure_ascii=False).replace("$json$", "$ json $")
            statements.append(
                "UPDATE interview_sessions SET "
                "legacy_overall_score = COALESCE(legacy_overall_score, overall_score), "
                f"overall_score = {new_score}, scoring_version = '{VERSION}', "
                f"messages_json = $json${payload}$json$, skill_evidence_json = NULL "
                f"WHERE id = {int(session['id'])};"
            )
    if args.apply:
        statements.append("COMMIT;")
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".sql", delete=False) as handle:
            handle.write("\n".join(statements))
            sql_path = handle.name
        env = dict(os.environ)
        env.setdefault("PGPASSWORD", "careernav")
        subprocess.run(
            [r"D:\pgsql18\pgsql\bin\psql.exe", "-h", "127.0.0.1", "-p", "5432",
             "-U", "careernav", "-d", "careernav", "-v", "ON_ERROR_STOP=1", "-f", sql_path],
            env=env, check=True,
        )
        Path(sql_path).unlink(missing_ok=True)
        print(f"Applied scoring version: {VERSION}")
    else:
        print("Dry run only. Add --apply to update the database.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
