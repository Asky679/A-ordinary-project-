#!/usr/bin/env python3
"""Safely import boss_python_jobs.csv into the existing jobs table."""
from __future__ import annotations

import csv
import hashlib
import json
import os
import re
from datetime import datetime
from pathlib import Path

import psycopg2
from psycopg2.extras import execute_values

SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_CSV = SCRIPT_DIR.parent / "raw-data" / "boss_python_jobs.csv"
if not DEFAULT_CSV.exists():
    DEFAULT_CSV = Path.home() / "Desktop" / "boss_python_jobs.csv"
CSV_PATH = Path(os.getenv("BOSS_PYTHON_CSV", str(DEFAULT_CSV)))
SOURCE = "boss_python_jobs.csv"
DATASET_NAME = "boss_python_jobs"
DATASET_YEAR = 2025
CONTENT_ORIGIN = "ORIGINAL"

SKILL_PATTERNS = [
    ("Python", [r"python"]), ("Java", [r"(?<![a-z])java(?!script)"]),
    ("JavaScript", [r"javascript", r"(?<![a-z])js(?![a-z])"]), ("TypeScript", [r"typescript"]),
    ("C++", [r"c\+\+"]), ("C#", [r"c#"]), ("Go", [r"golang", r"(?<![a-z])go(?![a-z])"]),
    ("SQL", [r"(?<![a-z])sql(?![a-z])"]), ("MySQL", [r"mysql"]), ("PostgreSQL", [r"postgresql", r"pgsql"]),
    ("Oracle", [r"oracle"]), ("Redis", [r"redis"]), ("MongoDB", [r"mongodb"]),
    ("Elasticsearch", [r"elasticsearch", r"elastic search"]), ("Linux", [r"linux"]),
    ("Docker", [r"docker"]), ("Kubernetes", [r"kubernetes", r"k8s"]), ("Git", [r"(?<![a-z])git(?![a-z])"]),
    ("Django", [r"django"]), ("Flask", [r"flask"]), ("FastAPI", [r"fastapi"]), ("Scrapy", [r"scrapy"]),
    ("Selenium", [r"selenium"]), ("Requests", [r"requests"]), ("Pandas", [r"pandas"]), ("NumPy", [r"numpy"]),
    ("PyTorch", [r"pytorch", r"torch"]), ("TensorFlow", [r"tensorflow"]), ("Spark", [r"spark"]),
    ("Hadoop", [r"hadoop"]), ("Hive", [r"hive"]), ("Kafka", [r"kafka"]), ("RabbitMQ", [r"rabbitmq"]),
    ("Nginx", [r"nginx"]), ("Shell", [r"shell"]), ("RESTful API", [r"restful", r"rest api"]),
    ("HTML", [r"html"]), ("CSS", [r"css"]), ("Vue", [r"vue"]), ("React", [r"react"]),
    ("ETL", [r"(?<![a-z])etl(?![a-z])"]), ("爬虫", [r"爬虫"]), ("数据分析", [r"数据分析"]),
    ("机器学习", [r"机器学习"]), ("深度学习", [r"深度学习"])
]
def clean(value: str | None) -> str:
    return (value or "").strip()


def split_lines(value: str) -> list[str]:
    text = clean(value)
    if not text:
        return []
    parts = [part.strip(" \t-•") for part in re.split(r"\r?\n+", text)]
    return [part for part in parts if part]


def extract_skills(*texts: str) -> list[str]:
    haystack = " ".join(filter(None, texts))
    result = []
    for display, patterns in SKILL_PATTERNS:
        if any(re.search(pattern, haystack, re.IGNORECASE) for pattern in patterns):
            result.append(display)
    return result


def parse_location(value: str) -> tuple[str, str | None]:
    parts = clean(value).split()
    if not parts:
        return "", None
    return parts[0], " ".join(parts[1:]) or None


def stable_identity(values: list[str]) -> str:
    return "|".join(clean(value) for value in values)


def build_row(values: list[str], row_number: int, file_mtime: datetime) -> tuple[str, tuple]:
    category, title, salary, location, experience, education, company, industry, company_size, online_status, responsibilities, requirements, job_description = values[:13]
    city, area = parse_location(location)
    responsibilities = clean(responsibilities)
    requirements = clean(requirements)
    job_description = clean(job_description)
    source_description = responsibilities or job_description
    skills = extract_skills(title, category, responsibilities, requirements, job_description)
    identity = stable_identity([title, company, location, salary, experience, education, source_description, requirements])
    digest = hashlib.sha1(identity.encode("utf-8")).hexdigest()
    external_id = f"boss-python-csv-{digest[:32]}"
    content_hash = hashlib.sha256(identity.encode("utf-8")).hexdigest()
    search_text = " ".join(filter(None, [title, company, city, area, salary, category, industry, company_size, " ".join(skills), source_description, requirements]))
    description = " | ".join(filter(None, [
        f"类别：{category}" if category else "",
        f"行业：{industry}" if industry else "",
        f"公司规模：{company_size}" if company_size else "",
        f"地点：{location}" if location else "",
        f"在线状态：{online_status}" if online_status else "",
    ]))
    params = (
        external_id, title, company, city or None, salary or None, education or None, experience or None,
        json.dumps(skills, ensure_ascii=False), json.dumps(split_lines(responsibilities), ensure_ascii=False),
        description or None, source_description or None, requirements or None, source_description or None, requirements or None,
        CONTENT_ORIGIN, SOURCE, None, file_mtime, None, None, datetime.now(), "ACTIVE", content_hash,
        DATASET_NAME, DATASET_YEAR, f"row-{row_number:04d}", DATASET_NAME, category or None, area,
        location or None, industry or None, company_size or None, search_text or None,
    )
    return external_id, params
def main() -> None:
    if not CSV_PATH.exists():
        raise SystemExit(f"CSV not found: {CSV_PATH}")
    with CSV_PATH.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.reader(handle)
        next(reader, None)
        rows = [row for row in reader if len(row) >= 13 and clean(row[1]) and clean(row[6])]

    file_mtime = datetime.fromtimestamp(CSV_PATH.stat().st_mtime)
    records: dict[str, tuple] = {}
    for index, values in enumerate(rows, start=1):
        external_id, params = build_row(values, index, file_mtime)
        records[external_id] = params

    connection = psycopg2.connect(
        host=os.getenv("DB_HOST", "127.0.0.1"), port=int(os.getenv("DB_PORT", "5432")),
        dbname=os.getenv("DB_NAME", "careernav"), user=os.getenv("DB_USER", "careernav"),
        password=os.getenv("DB_PASSWORD", ""),
    )
    sql = """
        INSERT INTO jobs (
            external_id, title, company, city, salary, education, experience,
            skills_json, responsibilities_json, description,
            job_description, job_requirements, original_job_description, original_job_requirements,
            content_origin, source, source_url, collected_at, published_at, valid_through, last_seen_at,
            status, content_hash, dataset_name, dataset_year, source_record_id, source_table,
            category, area, location, industry, company_size, search_text
        ) VALUES %s
        ON CONFLICT (external_id) DO NOTHING
    """
    try:
        with connection:
            with connection.cursor() as cursor:
                cursor.execute("SELECT count(*) FROM jobs")
                before = cursor.fetchone()[0]
                execute_values(cursor, sql, list(records.values()), page_size=100)
                cursor.execute("SELECT count(*) FROM jobs")
                after = cursor.fetchone()[0]
                inserted = after - before
                cursor.execute("SELECT count(*) FROM jobs WHERE source = %s", (SOURCE,))
                source_count = cursor.fetchone()[0]
        print(json.dumps({
            "csv_rows": len(rows), "unique_csv_rows": len(records), "inserted": inserted,
            "jobs_before": before, "jobs_after": after, "source_count": source_count,
            "source": SOURCE, "content_origin": CONTENT_ORIGIN,
        }, ensure_ascii=False))
    finally:
        connection.close()


if __name__ == "__main__":
    main()