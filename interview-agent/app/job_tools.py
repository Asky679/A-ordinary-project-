"""Tools for the resume -> job recommendation agent.

Design rule
-----------
The LLM is responsible for *understanding* (reading the resume, inventing good
search intents) and *expression* (writing the final report). It is NOT allowed
to invent match scores. Every score comes from the weighted algorithm that
already lives in the Spring Boot service, so results stay reproducible and
explainable.

Every tool here is a thin HTTP wrapper over the existing REST API - no business
logic is duplicated in Python, which keeps the two agents from drifting apart.

Tools never raise: on failure they return {"ok": false, "error": ...} so the
graph can degrade gracefully instead of crashing mid-run.
"""
from __future__ import annotations

import json
import os
from typing import Any

import requests
from langchain_core.tools import tool

API_BASE = os.getenv("CAREERNAV_API_BASE", "http://127.0.0.1:8080/api").rstrip("/")
API_TIMEOUT = int(os.getenv("CAREERNAV_API_TIMEOUT", "60"))

# job ids that were really returned by a tool, keyed by resume id.
# finalize_recommendation() validates against this registry so the model can
# never recommend a job that does not exist in the database.
_KNOWN_JOB_IDS: dict[int, set[int]] = {}


def _remember(resume_id: int, job_ids: list[int]) -> None:
    if resume_id is None:
        return
    bucket = _KNOWN_JOB_IDS.setdefault(int(resume_id), set())
    bucket.update(int(job_id) for job_id in job_ids if job_id is not None)


def known_job_ids(resume_id: int) -> set[int]:
    return set(_KNOWN_JOB_IDS.get(int(resume_id), set()))


def _dump(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False)


def _brief(job: dict) -> dict:
    """Compact job view for the model's context window (no long JD text)."""
    if not isinstance(job, dict):
        return {}
    return {
        "jobId": job.get("id"),
        "title": job.get("title"),
        "company": job.get("company"),
        "city": job.get("city"),
        "salary": job.get("salary"),
        "education": job.get("education"),
        "experience": job.get("experience"),
        "skills": job.get("skills") or [],
        "contentOrigin": job.get("contentOrigin"),
    }


def _get(path: str, params: dict | None = None) -> Any:
    response = requests.get(API_BASE + path, params=params or {}, timeout=API_TIMEOUT)
    response.raise_for_status()
    body = response.json()
    if isinstance(body, dict) and body.get("success") is False:
        raise RuntimeError(str(body.get("message") or "接口返回失败"))
    if isinstance(body, dict) and "data" in body:
        return body["data"]
    return body


def _call(path: str, params: dict | None, transform) -> str:
    try:
        data = _get(path, params)
        return _dump({"ok": True, "data": transform(data)})
    except Exception as exc:  # noqa: BLE001 - surfaced to the graph, never raised
        return _dump({"ok": False, "error": type(exc).__name__ + ": " + str(exc)})


@tool
def read_resume(resume_id: int) -> str:
    """读取简历的结构化信息：目标岗位、城市、学历、技能、项目、实习经历。

    出于隐私考虑，手机号和邮箱不会返回给模型。
    """
    def transform(data: dict) -> dict:
        return {
            "resumeId": data.get("id"),
            "targetRole": data.get("targetRole"),
            "city": data.get("city"),
            "education": data.get("education"),
            "school": data.get("school"),
            "major": data.get("major"),
            "graduationYear": data.get("graduationYear"),
            "skills": data.get("skills") or [],
            "projects": data.get("projects") or [],
            "experiences": data.get("experiences") or [],
            "selfEvaluation": data.get("selfEvaluation"),
        }

    return _call("/resumes/" + str(int(resume_id)), None, transform)


@tool
def search_jobs_hybrid(resume_id: int, query: str, city: str = "", limit: int = 15) -> str:
    """语义检索岗位（向量库混合检索）。

    query 用「岗位方向 + 核心技能」的形式，例如「Python 后端 Django MySQL」，
    不要直接粘贴整份简历。
    """
    size = max(1, min(int(limit or 15), 30))

    def transform(rows: list) -> list:
        briefs = [_brief(row) for row in (rows or [])]
        _remember(resume_id, [item["jobId"] for item in briefs])
        return briefs

    return _call("/jobs/hybrid", {"query": query, "city": city, "limit": size}, transform)


@tool
def filter_jobs(resume_id: int, keyword: str = "", city: str = "", limit: int = 20) -> str:
    """按关键词和城市做结构化过滤，用于硬性条件筛选（例如城市不限、必须含某技术）。"""
    size = max(1, min(int(limit or 20), 50))

    def transform(rows: list) -> list:
        briefs = [_brief(row) for row in (rows or [])]
        _remember(resume_id, [item["jobId"] for item in briefs])
        return briefs

    return _call("/jobs", {"keyword": keyword, "city": city, "limit": size}, transform)


@tool
def recommend_jobs(resume_id: int, city: str = "", limit: int = 12, excluded_cities: str = "") -> str:
    """用项目现成的加权算法给岗位打分排序。

    返回每个岗位的 matchScore、matchLevel、命中技能、缺失技能，以及
    scoreBreakdown（技能/方向/经验/学历/城市/证据六个维度的贡献值）。
    分数完全由算法产生，可复现、可解释。
    """
    size = max(1, min(int(limit or 12), 30))

    def transform(rows: list) -> list:
        result = []
        for row in rows or []:
            job = _brief(row.get("job") or {})
            result.append({
                "job": job,
                "matchScore": row.get("matchScore"),
                "matchLevel": row.get("matchLevel"),
                "matchedSkills": row.get("matchedSkills") or [],
                "missingSkills": row.get("missingSkills") or [],
                "reasons": row.get("reasons") or [],
                "scoreBreakdown": row.get("scoreBreakdown") or [],
            })
        _remember(resume_id, [item["job"].get("jobId") for item in result])
        return result

    return _call("/jobs/recommend", {
        "resumeId": int(resume_id), "city": city, "limit": size, "excludedCities": excluded_cities,
    }, transform)


@tool
def get_job_detail(job_id: int) -> str:
    """读取岗位完整信息，含 jobDescription（岗位职责）、jobRequirements（任职要求）。

    contentOrigin 为 AI_GENERATED 时说明该 JD 是模型补全的，不是招聘原文，
    引用时必须标注。
    """
    def transform(data: dict) -> dict:
        return {
            "jobId": data.get("id"),
            "title": data.get("title"),
            "company": data.get("company"),
            "city": data.get("city"),
            "salary": data.get("salary"),
            "education": data.get("education"),
            "experience": data.get("experience"),
            "skills": data.get("skills") or [],
            "jobDescription": data.get("jobDescription") or data.get("description"),
            "jobRequirements": data.get("jobRequirements"),
            "contentOrigin": data.get("contentOrigin"),
            "source": data.get("source"),
            "sourceUrl": data.get("sourceUrl"),
        }

    return _call("/jobs/" + str(int(job_id)), None, transform)


@tool
def finalize_recommendation(resume_id: int, job_ids: list[int], summary: str) -> str:
    """锁定最终推荐结果（流程的最后一步）。

    job_ids 必须来自前面工具真实返回过的岗位 ID，否则会被拒绝。这是防止模型
    编造岗位的闸门。
    """
    allowed = known_job_ids(resume_id)
    requested = [int(job_id) for job_id in (job_ids or [])]
    rejected = [job_id for job_id in requested if job_id not in allowed]
    if rejected:
        return _dump({
            "ok": False,
            "error": "以下岗位不在本次检索结果中，可能是编造的：" + _dump(rejected),
            "allowed": sorted(allowed)[:50],
        })
    return _dump({
        "ok": True,
        "data": {
            "resumeId": resume_id,
            "jobIds": requested,
            "summary": summary,
            "validatedAgainst": len(allowed),
        },
    })


JOB_AGENT_TOOLS = [
    read_resume,
    search_jobs_hybrid,
    filter_jobs,
    recommend_jobs,
    get_job_detail,
    finalize_recommendation,
]
