#!/usr/bin/env python3
"""把 boss_jobs_matrix 两个 CSV 完整导入 jobs 表（幂等，不删除任何已有数据）。

数据来源
--------
data/boss-jobs-2026/boss_jobs_matrix.csv      1,030 行
data/boss-jobs-2026/boss_jobs_matrix_1.csv    4,980 行

两个文件有重叠（A 的 1,027 个岗位键全部出现在 B 中），因此用内容哈希生成
确定的 external_id，配合 ON CONFLICT DO NOTHING，保证：
  * 每一行都会被尝试导入，不做任何行级筛选
  * 完全相同的行只入库一次，不会在列表里出现重复岗位
  * 重复执行本脚本不会产生新数据

字段映射
--------
城市→city  方向→category  职位→title  薪资→salary  地点→location  经验→experience
学历→education  公司→company  行业→industry  公司规模→company_size
岗位职责→job_description  任职要求→job_requirements  职位描述→description
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
import sys
from datetime import datetime
from pathlib import Path

import psycopg2
from psycopg2.extras import execute_values

ROOT = Path(__file__).resolve().parent.parent
DATA_DIR = ROOT / "data" / "boss-jobs-2026"
FILES = ["boss_jobs_matrix.csv", "boss_jobs_matrix_1.csv"]

DB = dict(host="127.0.0.1", port=5432, dbname="careernav", user="careernav", password="")
SOURCE = "boss_jobs_matrix"
DATASET_NAME = "boss_jobs_matrix_2026"
DATASET_YEAR = 2026
CONTENT_ORIGIN = "ORIGINAL"

SKILL_PATTERNS = [
    ("Python", [r"python"]), ("Java", [r"(?<![a-z])java(?!script)"]),
    ("JavaScript", [r"javascript", r"(?<![a-z])js(?![a-z])"]), ("TypeScript", [r"typescript"]),
    ("C++", [r"c\+\+"]), ("C#", [r"c#"]), ("Go", [r"golang", r"(?<![a-z])go(?![a-z])"]),
    ("PHP", [r"(?<![a-z])php(?![a-z])"]), ("Rust", [r"(?<![a-z])rust(?![a-z])"]),
    ("SQL", [r"(?<![a-z])sql(?![a-z])"]), ("MySQL", [r"mysql"]),
    ("PostgreSQL", [r"postgresql", r"pgsql"]), ("Oracle", [r"oracle"]),
    ("Redis", [r"redis"]), ("MongoDB", [r"mongodb"]),
    ("Elasticsearch", [r"elasticsearch", r"elastic search"]),
    ("Linux", [r"linux"]), ("Docker", [r"docker"]),
    ("Kubernetes", [r"kubernetes", r"k8s"]), ("Git", [r"(?<![a-z])git(?![a-z])"]),
    ("Django", [r"django"]), ("Flask", [r"flask"]), ("FastAPI", [r"fastapi"]),
    ("Scrapy", [r"scrapy"]), ("Selenium", [r"selenium"]),
    ("Pandas", [r"pandas"]), ("NumPy", [r"numpy"]),
    ("PyTorch", [r"pytorch", r"(?<![a-z])torch(?![a-z])"]), ("TensorFlow", [r"tensorflow"]),
    ("Spark", [r"spark"]), ("Hadoop", [r"hadoop"]), ("Hive", [r"hive"]),
    ("Kafka", [r"kafka"]), ("RabbitMQ", [r"rabbitmq"]), ("Flink", [r"flink"]),
    ("Nginx", [r"nginx"]), ("Shell", [r"shell"]), ("RESTful API", [r"restful", r"rest api"]),
    ("HTML", [r"html"]), ("CSS", [r"css"]), ("Vue", [r"vue"]), ("React", [r"react"]),
    ("Spring", [r"spring"]), ("MyBatis", [r"mybatis"]), ("JVM", [r"(?<![a-z])jvm(?![a-z])"]),
    ("ETL", [r"(?<![a-z])etl(?![a-z])"]), ("CI/CD", [r"ci/cd", r"jenkins"]),
    ("微服务", [r"微服务"]), ("分布式", [r"分布式"]), ("高并发", [r"高并发"]),
    ("数据分析", [r"数据分析"]), ("机器学习", [r"机器学习"]), ("深度学习", [r"深度学习"]),
]


def clean(value) -> str:
    return (value or "").strip()


def split_lines(value: str) -> list[str]:
    text = clean(value)
    if not text:
        return []
    parts = [re.sub(r"^[\s\-•·\d]+[、.)）]?\s*", "", p).strip() for p in re.split(r"\r?\n+", text)]
    return [p for p in parts if p]


def extract_skills(*texts: str) -> list[str]:
    haystack = " ".join(filter(None, texts))
    found = []
    for label, patterns in SKILL_PATTERNS:
        if any(re.search(p, haystack, re.IGNORECASE) for p in patterns):
            found.append(label)
    return found


def parse_location(value: str) -> tuple[str, str | None]:
    parts = clean(value).split()
    if not parts:
        return "", None
    return parts[0], " ".join(parts[1:]) or None


def build_record(row: dict, file_name: str, now: datetime) -> tuple:
    city_col = clean(row.get("城市"))
    category = clean(row.get("方向"))
    title = clean(row.get("职位"))
    salary = clean(row.get("薪资"))
    location = clean(row.get("地点"))
    experience = clean(row.get("经验"))
    education = clean(row.get("学历"))
    company = clean(row.get("公司"))
    industry = clean(row.get("行业"))
    company_size = clean(row.get("公司规模"))
    responsibilities = clean(row.get("岗位职责"))
    requirements = clean(row.get("任职要求"))
    description = clean(row.get("职位描述"))

    city = city_col or parse_location(location)[0]
    area = parse_location(location)[1]

    # 可用字段：职责缺失时退回职位描述；要求缺失时用职位描述补齐
    job_description = responsibilities or description
    job_requirements = requirements or description

    skills = extract_skills(title, category, industry, job_description, job_requirements)
    search_text = " ".join(filter(None, [
        title, company, city, area, salary, category, industry, company_size,
        " ".join(skills), description, job_description, job_requirements,
    ]))

    identity = "|".join([city, title, company, salary, location, responsibilities, requirements])
    external_id = "bossmatrix-" + hashlib.sha1(identity.encode("utf-8")).hexdigest()[:32]
    content_hash = hashlib.sha256(identity.encode("utf-8")).hexdigest()

    return (
        external_id, title, company, city or None, salary or None, education or None,
        experience or None, json.dumps(skills, ensure_ascii=False),
        json.dumps(split_lines(job_description), ensure_ascii=False),
        description or None, SOURCE, None, now, now, "ACTIVE", content_hash,
        DATASET_NAME, DATASET_YEAR, None, file_name, category or None, area, location or None,
        industry or None, company_size or None, search_text, None,
        job_description or None, job_requirements or None,
        responsibilities or None, requirements or None, CONTENT_ORIGIN,
    )


COLUMNS = """external_id, title, company, city, salary, education, experience,
    skills_json, responsibilities_json, description, source, source_url,
    collected_at, last_seen_at, status, content_hash, dataset_name, dataset_year,
    source_record_id, source_table, category, area, location, industry, company_size,
    search_text, license, job_description, job_requirements,
    original_job_description, original_job_requirements, content_origin"""


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    now = datetime.now()
    records, per_file = [], {}
    for name in FILES:
        path = DATA_DIR / name
        if not path.exists():
            print("缺失文件: " + str(path))
            return 1
        with path.open(encoding="utf-8-sig", newline="") as fh:
            rows = list(csv.DictReader(fh))
        per_file[name] = len(rows)
        records.extend(build_record(r, name, now) for r in rows)

    unique = {r[0]: r for r in records}
    print("读取文件:")
    for name, n in per_file.items():
        print("   %-28s %5d 行" % (name, n))
    print("合计 %d 行 → 内容去重后 %d 条唯一岗位" % (len(records), len(unique)))

    with_req = sum(1 for r in unique.values() if r[28])
    with_desc = sum(1 for r in unique.values() if r[27])
    print("   有岗位职责: %d / %d" % (with_desc, len(unique)))
    print("   有任职要求: %d / %d  (%.1f%%)" % (with_req, len(unique), 100 * with_req / len(unique)))

    if args.dry_run:
        print("\n[dry-run] 未写入数据库")
        return 0

    conn = psycopg2.connect(**DB)
    try:
        with conn, conn.cursor() as cur:
            before = cur.execute("SELECT count(*) FROM jobs") or cur.fetchone()[0]
            execute_values(
                cur,
                "INSERT INTO jobs (" + COLUMNS + ") VALUES %s ON CONFLICT (external_id) DO NOTHING",
                list(unique.values()),
                page_size=200,
            )
            cur.execute("SELECT count(*) FROM jobs")
            after = cur.fetchone()[0]
            # execute_values 会分多条语句执行，rowcount 只反映最后一批，
            # 因此用表总数差值作为真实插入量。
            inserted = after - before
        print("\n导入完成：新插入 %d 条，跳过已存在 %d 条" % (inserted, len(unique) - inserted))
        print("jobs 表：%d → %d 条" % (before, after))
    finally:
        conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
