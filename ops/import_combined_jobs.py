#!/usr/bin/env python3
"""导入 careernav_jobs_combined.csv，并在入库前做多层查重。

查重规则
--------
1. CSV 内：external_id 完全相同的记录只保留信息最完整的一条。
2. CSV 内：content_hash 完全相同的记录只保留信息最完整的一条。
3. 与 jobs 表：external_id 已存在则跳过。
4. 与 jobs 表：content_hash 已存在则跳过。
5. 与 jobs 表：规范化后的「岗位名 + 公司 + 城市」已存在则跳过。
   这一条用于识别同一岗位在不同采集批次中的重复，尤其是 BOSS 旧数据。

脚本只 INSERT，不 UPDATE、不 DELETE，因此不会覆盖 jobs 表已有内容。
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path
from typing import Any

import psycopg2
from psycopg2.extras import execute_values

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_CSV = ROOT / "data" / "imports" / "careernav_jobs_combined.csv"
DEFAULT_REPORT = ROOT / "output" / "combined_jobs_import_report.json"
DB = dict(host="127.0.0.1", port=5432, dbname="careernav", user="careernav", password="")
SOURCE_TABLE = "careernav_jobs_combined.csv"

COLUMNS = """external_id, title, company, city, salary, education, experience,
    skills_json, responsibilities_json, description, source, source_url,
    collected_at, published_at, last_seen_at, status, content_hash,
    dataset_name, dataset_year, source_record_id, source_table, category, area,
    location, search_text, job_description, job_requirements,
    original_job_description, original_job_requirements, content_origin"""

IMPORTANT_FIELDS = (
    "external_id", "source_platform", "source_job_id", "source_url", "company_name",
    "job_title", "responsibilities", "requirements", "description_text", "city",
    "district", "salary_raw", "experience", "education", "job_type", "published_at",
    "job_status", "direction", "skills", "content_hash",
)


def clean(value: Any) -> str:
    return str(value or "").strip()


def norm(value: Any) -> str:
    return re.sub(r"\s+", "", clean(value).lower())


def business_key(row: dict[str, Any]) -> tuple[str, str, str]:
    return (norm(row.get("job_title")), norm(row.get("company_name")), norm(normalize_city(row.get("city"))))


def quality_score(row: dict[str, Any]) -> int:
    score = sum(1 for field in IMPORTANT_FIELDS if clean(row.get(field)))
    score += len(clean(row.get("responsibilities"))) // 50
    score += len(clean(row.get("requirements"))) // 50
    score += len(clean(row.get("description_text"))) // 80
    return score


def keep_best(groups: dict[str, list[dict[str, Any]]]) -> tuple[list[dict[str, Any]], int]:
    kept: list[dict[str, Any]] = []
    removed = 0
    for rows in groups.values():
        if len(rows) == 1:
            kept.append(rows[0])
            continue
        removed += len(rows) - 1
        kept.append(max(rows, key=quality_score))
    return kept, removed


def dedup_rows(rows: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], dict[str, int]]:
    by_external: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        by_external[clean(row.get("external_id"))].append(row)
    stage1, removed_external = keep_best(by_external)

    by_hash: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in stage1:
        by_hash[clean(row.get("content_hash"))].append(row)
    stage2, removed_hash = keep_best(by_hash)
    return stage2, {
        "external_id_duplicates_removed": removed_external,
        "content_hash_duplicates_removed": removed_hash,
    }


CITY_TRANSLATIONS = {
    "beijing": "北京", "shanghai": "上海", "shenzhen": "深圳", "guangzhou": "广州",
    "hangzhou": "杭州", "chengdu": "成都", "nanjing": "南京", "wuhan": "武汉",
    "xian": "西安", "suzhou": "苏州", "changsha": "长沙", "fuzhou": "福州",
    "xiamen": "厦门", "hong kong": "香港", "taipei": "台北", "taipei city": "台北",
    "hsinchu": "新竹", "taiwan": "台湾", "china": "全国",
}


def normalize_city(value: str) -> str:
    """把常见英文城市、Office Based 前缀和多城市写法统一成中文。"""
    text = clean(value)
    if not text:
        return ""
    parts = [part.strip() for part in re.split(r"[;；|]|(?:\s+/\s+)", text) if part.strip()]
    translated: list[str] = []
    for part in parts:
        candidate = re.sub(r"^office\s+based\s*[-–—]\s*", "", part, flags=re.IGNORECASE).strip()
        city_part = candidate.split(",", 1)[0].strip()
        mapped = CITY_TRANSLATIONS.get(city_part.lower(), city_part)
        if len(mapped) > 2 and mapped.endswith("市"):
            mapped = mapped[:-1]
        if mapped and mapped not in translated:
            translated.append(mapped)
    return "、".join(translated)


def split_skills(value: str) -> list[str]:
    text = clean(value)
    if not text:
        return []
    parts = [part.strip() for part in re.split(r"[|,，;；、]+", text)]
    return list(dict.fromkeys(part for part in parts if part))


def split_lines(value: str) -> list[str]:
    text = clean(value)
    if not text:
        return []
    parts = []
    for line in re.split(r"\r?\n+", text):
        line = re.sub(r"^[\s\-•·\d]+[、.)）]?\s*", "", line).strip()
        if line:
            parts.append(line)
    return parts


def parse_datetime(value: str) -> datetime | None:
    text = clean(value)
    if not text:
        return None
    try:
        return datetime.fromisoformat(text.replace("Z", "+00:00")).replace(tzinfo=None)
    except ValueError:
        return None


def normalize_status(value: str) -> str:
    status = clean(value).lower()
    if not status or status in {"open", "active", "在线", "招聘中"}:
        return "ACTIVE"
    if "pause" in status or "暂停" in status:
        return "PAUSED"
    if "closed" in status or "关闭" in status or "结束" in status:
        return "CLOSED"
    return "ACTIVE"


def clip(value: str | None, length: int) -> str | None:
    text = clean(value)
    return text[:length] if text else None


def build_record(row: dict[str, Any], now: datetime) -> tuple:
    title = clean(row.get("job_title"))
    company = clean(row.get("company_name"))
    city = normalize_city(row.get("city"))
    district = clean(row.get("district"))
    direction = clean(row.get("direction"))
    salary = clean(row.get("salary_raw"))
    experience = clean(row.get("experience"))
    education = clean(row.get("education"))
    responsibilities = clean(row.get("responsibilities"))
    requirements = clean(row.get("requirements"))
    description = clean(row.get("description_text"))
    source_url = clean(row.get("source_url"))
    skills = split_skills(row.get("skills"))
    job_description = responsibilities or description
    job_requirements = requirements
    job_type = clean(row.get("job_type"))
    location = " ".join(part for part in (city, district) if part)

    published_at = parse_datetime(row.get("published_at", ""))
    first_seen_at = parse_datetime(row.get("first_seen_at", "")) or now
    last_seen_at = parse_datetime(row.get("last_seen_at", "")) or first_seen_at
    dataset_year = (last_seen_at or published_at or now).year
    source_record_id = clean(row.get("source_job_id")) or clean(row.get("external_id"))
    content_hash = clean(row.get("content_hash"))
    if not content_hash:
        content_hash = hashlib.sha256(
            "|".join([title, company, city, salary, job_description, job_requirements]).encode("utf-8")
        ).hexdigest()

    search_text = " ".join(filter(None, [
        title, company, city, district, salary, experience, education, direction,
        job_type, " ".join(skills), description, job_description, job_requirements,
    ]))

    return (
        clip(row.get("external_id"), 255),
        clip(title, 255),
        clip(company, 255),
        clip(city, 255),
        clip(salary, 255),
        clip(education, 255),
        clip(experience, 255),
        json.dumps(skills, ensure_ascii=False),
        json.dumps(split_lines(job_description), ensure_ascii=False),
        description or None,
        clip(row.get("source_platform"), 255),
        clip(source_url, 255),
        first_seen_at,
        published_at,
        last_seen_at,
        normalize_status(row.get("job_status")),
        clip(content_hash, 255),
        clip(row.get("dataset_name"), 120),
        dataset_year,
        clip(source_record_id, 120),
        SOURCE_TABLE,
        clip(direction, 80),
        clip(district, 120),
        clip(location, 255),
        search_text or None,
        job_description or None,
        job_requirements or None,
        responsibilities or None,
        requirements or None,
        "ORIGINAL",
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="查重后导入 careernav_jobs_combined.csv")
    parser.add_argument("--csv", default=str(DEFAULT_CSV), help="CSV 文件路径")
    parser.add_argument("--report", default=str(DEFAULT_REPORT), help="导入报告 JSON 路径")
    parser.add_argument("--dry-run", action="store_true", help="只查重，不写入数据库")
    args = parser.parse_args()

    csv_path = Path(args.csv)
    if not csv_path.exists():
        print(f"CSV 不存在：{csv_path}")
        return 1

    now = datetime.now()
    with csv_path.open(encoding="utf-8-sig", newline="") as handle:
        raw_rows = list(csv.DictReader(handle))
    if not raw_rows:
        print("CSV 没有数据")
        return 1

    unique_rows, dedup = dedup_rows(raw_rows)
    pairs = [(row, build_record(row, now)) for row in unique_rows]
    pairs = [(row, record) for row, record in pairs if record[0] and record[1] and record[2]]
    unique_rows = [row for row, _ in pairs]
    records = [record for _, record in pairs]

    conn = psycopg2.connect(**DB)
    try:
        with conn, conn.cursor() as cur:
            cur.execute("SELECT count(*) FROM jobs")
            before = cur.fetchone()[0]
            cur.execute("SELECT external_id, content_hash, title, company, city FROM jobs")
            existing = cur.fetchall()

        existing_external = {row[0] for row in existing if row[0]}
        existing_hash = {row[1] for row in existing if row[1]}
        existing_key = {(norm(row[2]), norm(row[3]), norm(normalize_city(row[4]))) for row in existing}

        accepted = []
        skipped = Counter()
        for row, record in pairs:
            external_id, content_hash = record[0], record[16]
            key = business_key(row)
            if external_id in existing_external:
                skipped["existing_external_id"] += 1
            elif content_hash in existing_hash:
                skipped["existing_content_hash"] += 1
            elif key in existing_key:
                skipped["existing_title_company_city"] += 1
            else:
                accepted.append(record)
                existing_external.add(external_id)
                existing_hash.add(content_hash)
                existing_key.add(key)

        report = {
            "csv": str(csv_path),
            "source_rows": len(raw_rows),
            **dedup,
            "after_csv_dedup": len(unique_rows),
            "skipped_against_database": dict(skipped),
            "candidate_rows": len(accepted),
            "before_database_count": before,
            "dry_run": bool(args.dry_run),
            "source_platform_candidates": dict(Counter(record[10] for record in accepted)),
            "status_candidates": dict(Counter(record[15] for record in accepted)),
            "with_job_description": sum(bool(record[25]) for record in accepted),
            "with_job_requirements": sum(bool(record[26]) for record in accepted),
        }

        if not args.dry_run:
            with conn, conn.cursor() as cur:
                execute_values(
                    cur,
                    "INSERT INTO jobs (" + COLUMNS + ") VALUES %s ON CONFLICT (external_id) DO NOTHING",
                    accepted,
                    page_size=200,
                )
                cur.execute("SELECT count(*) FROM jobs")
                after = cur.fetchone()[0]
            report["inserted"] = after - before
            report["after_database_count"] = after
        else:
            report["inserted"] = 0
            report["after_database_count"] = before

        report_path = Path(args.report)
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0
    finally:
        conn.close()


if __name__ == "__main__":
    raise SystemExit(main())