import json
import os
import re
from pathlib import Path
from typing import Any

import requests
from langchain_core.tools import tool

CONTEXTS: dict[str, dict[str, Any]] = {}

EXPERIENCE_PATTERNS_PATH = Path(__file__).resolve().parents[1] / "data" / "interview-experiences" / "patterns.jsonl"


def _load_experience_patterns() -> list[dict[str, Any]]:
    try:
        lines = EXPERIENCE_PATTERNS_PATH.read_text(encoding="utf-8").splitlines()
    except OSError:
        return []
    rows = []
    for line in lines:
        try:
            item = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(item, dict) and item.get("question"):
            rows.append(item)
    return rows


EXPERIENCE_PATTERNS = _load_experience_patterns()


def _retrieval_tokens(text: str) -> set[str]:
    value = (text or "").lower()
    tokens = set(re.findall(r"[a-z][a-z0-9+#._-]{1,24}", value))
    for sequence in re.findall(r"[\u4e00-\u9fff]+", value):
        if 2 <= len(sequence) <= 8:
            tokens.add(sequence)
        for index in range(len(sequence) - 1):
            tokens.add(sequence[index:index + 2])
    return {token for token in tokens if token.strip()}


QUESTION_BANK = {
    "self_intro": [
        "请用两分钟介绍你自己，并重点说明一个和目标岗位最相关的项目。",
        "请说明你的技术方向，以及你为什么选择这个岗位。",
    ],
    "project": [
        "请选择一个最有代表性的项目，说明你的个人职责、技术选型和一个具体难点。",
        "你在这个项目里做了哪些取舍？如果重新设计，你会改变什么？",
        "项目中最能证明你技术能力的一个数据指标是什么？基线是多少？",
    ],
    "backend": [
        "请说明一次接口性能优化的完整过程，包括定位、方案和结果。",
        "你如何保证数据库并发写入的正确性？",
        "Redis 缓存与数据库不一致时，你会如何处理？",
    ],
    "python": [
        "请说明 Python 异步编程和线程模型的区别及适用场景。",
        "Django、Flask、FastAPI 你会如何选型？",
        "请说明一次 Python 服务的性能排查过程。",
    ],
    "system": [
        "如果线上接口响应时间突然升高，你会如何排查？",
        "请说明一次你处理故障或异常的完整过程。",
        "如何设计监控、日志和告警来提前发现问题？",
    ],
    "behavior": [
        "请讲一次团队协作中你推动问题解决的经历。",
        "你遇到过最有挑战的技术问题是什么，你如何学习和解决？",
    ],
    "reverse": ["你有什么问题想反问面试官？"],
}


def set_context(session_id: str, payload: dict[str, Any]) -> None:
    context = CONTEXTS.setdefault(session_id, {})
    context.update(payload)
    context.setdefault("memory", [])
    context.setdefault("history", [])
    context.setdefault("round", 1)


def get_context(session_id: str) -> dict[str, Any]:
    return CONTEXTS.setdefault(session_id, {"memory": [], "history": [], "round": 1})


def context_public(session_id: str) -> dict[str, Any]:
    context = get_context(session_id)
    return {
        "session_id": session_id,
        "round": context.get("round", 1),
        "difficulty": context.get("difficulty", "标准"),
        "resume": context.get("resume", {}),
        "job": context.get("job", {}),
        "memory": context.get("memory", []),
        "history": context.get("history", []),
    }


def _job_skills(session_id: str) -> list[str]:
    context = get_context(session_id)
    job = context.get("job") or {}
    skills = job.get("skills") or job.get("skillKeywords") or []
    return [str(skill) for skill in skills if skill]


def _resume_text(session_id: str) -> str:
    context = get_context(session_id)
    resume = context.get("resume") or {}
    projects = resume.get("projects") or []
    project_text = " ".join(
        f"{item.get('name', '')} {item.get('techStack', '')} {item.get('description', '')} {item.get('highlight', '')}"
        for item in projects
        if isinstance(item, dict)
    )
    return " ".join([
        str(resume.get("targetRole", "")),
        " ".join(str(skill) for skill in (resume.get("skills") or [])),
        project_text,
        str(resume.get("selfEvaluation", "")),
    ])


def _select_stage(session_id: str) -> str:
    context = get_context(session_id)
    round_number = int(context.get("round", 1))
    resume_text = _resume_text(session_id).lower()
    job_text = " ".join(_job_skills(session_id)).lower() + " " + str((context.get("job") or {}).get("title", "")).lower()
    if round_number <= 1:
        return "self_intro"
    if round_number == 2 or "项目" in resume_text or "project" in resume_text:
        return "project"
    if any(term in job_text for term in ("python", "django", "flask", "fastapi")):
        return "python"
    if any(term in job_text for term in ("java", "spring", "backend", "后端", "mysql", "redis")):
        return "backend"
    if any(term in job_text for term in ("运维", "linux", "docker", "devops", "sre")):
        return "system"
    return "behavior"
@tool
def read_session_context(session_id: str) -> str:
    """读取当前面试会话中的简历、岗位、轮次和历史记忆。"""
    return json.dumps(context_public(session_id), ensure_ascii=False)


@tool
def get_job_requirements(session_id: str) -> str:
    """读取当前岗位的技能、职责、任职要求和候选人技能。"""
    context = get_context(session_id)
    job = context.get("job") or {}
    resume = context.get("resume") or {}
    return json.dumps({
        "title": job.get("title"),
        "skills": _job_skills(session_id),
        "jobRequirements": job.get("jobRequirements") or job.get("description"),
        "candidateSkills": resume.get("skills") or [],
        "targetRole": resume.get("targetRole"),
    }, ensure_ascii=False)


@tool
def search_question_bank(session_id: str, keyword: str, stage: str = "") -> str:
    """根据关键词和面试阶段检索问题库。"""
    selected_stage = stage or _select_stage(session_id)
    candidates = list(QUESTION_BANK.get(selected_stage, []))
    candidates.extend(QUESTION_BANK.get("behavior", []))
    if keyword:
        candidates.extend([
            f"你提到 {keyword}，请说明它的核心原理、使用场景和你实际遇到的边界问题。",
            f"如果让你基于 {keyword} 设计一个高可用方案，你会如何分层和容错？",
        ])
    return json.dumps({"stage": selected_stage, "questions": candidates[:6]}, ensure_ascii=False)


@tool
def evaluate_candidate_answer(session_id: str, answer: str) -> str:
    """评估候选人回答的结构、技术深度、个人贡献和量化证据。"""
    text = (answer or "").strip()
    lower = text.lower()
    score = 45
    signals: list[str] = []
    gaps: list[str] = []
    if len(text) >= 80:
        score += 10
        signals.append("内容较完整")
    else:
        gaps.append("回答长度偏短")
    star_terms = ["首先", "其次", "最后", "背景", "任务", "行动", "结果", "star", "步骤"]
    if sum(term in lower for term in star_terms) >= 2:
        score += 12
        signals.append("表达有结构")
    else:
        gaps.append("缺少 STAR 结构")
    job_skills = _job_skills(session_id)
    hits = [skill for skill in job_skills if skill.lower() in lower]
    score += min(15, len(hits) * 4)
    if hits:
        signals.append("技术关键词命中")
    else:
        gaps.append("没有命中岗位技术关键词")
    if any(char.isdigit() for char in text):
        score += 7
        signals.append("使用了数据证据")
    else:
        gaps.append("缺少量化结果")
    if any(term in lower for term in ["我负责", "我主导", "我设计", "我实现", "我优化", "我个人"]):
        score += 8
        signals.append("说明了个人贡献")
    else:
        gaps.append("个人贡献不够明确")
    score = max(45, min(96, score))
    feedback = "；".join(signals) if signals else "回答方向正确，但缺少结构和证据"
    if gaps:
        feedback += "。建议补充：" + "、".join(gaps[:3])
    return json.dumps({
        "score": score,
        "feedback": feedback,
        "signals": signals,
        "gaps": gaps,
        "matchedSkills": hits,
    }, ensure_ascii=False)


@tool
def save_interview_memory(session_id: str, note: str) -> str:
    """把候选人回答中的关键事实、薄弱点或待追问事项写入面试记忆。"""
    context = get_context(session_id)
    note = (note or "").strip()
    if note and note not in context["memory"]:
        context["memory"].append(note)
    return json.dumps({"saved": bool(note), "memory": context["memory"]}, ensure_ascii=False)


@tool
def get_interview_progress(session_id: str) -> str:
    """读取当前轮次、最大轮次、是否符合结束条件以及历史评分。"""
    context = get_context(session_id)
    history = context.get("history", [])
    scores = [item.get("score", 0) for item in history if isinstance(item, dict)]
    return json.dumps({
        "round": context.get("round", 1),
        "maxRounds": context.get("max_rounds", 4),
        "done": int(context.get("round", 1)) >= int(context.get("max_rounds", 4)),
        "averageScore": round(sum(scores) / len(scores), 1) if scores else 0,
        "memory": context.get("memory", []),
    }, ensure_ascii=False)


# --------------------------------------------------------------------------- #
# Qdrant-backed interview question retrieval
# --------------------------------------------------------------------------- #
INTERVIEW_QDRANT_URL = os.getenv("INTERVIEW_QDRANT_URL", "http://127.0.0.1:6333")
INTERVIEW_COLLECTION = os.getenv("INTERVIEW_COLLECTION", "careernav_interview_questions")
INTERVIEW_EMBEDDING_URL = os.getenv("INTERVIEW_EMBEDDING_URL", "http://127.0.0.1:5002/v1/embeddings")
KB_STAGES = {"self_intro", "backend", "system", "behavior", "algorithm", "reverse", "offer", "process", "ai"}


def _kb_embed(text: str) -> list:
    response = requests.post(INTERVIEW_EMBEDDING_URL, json={"input": text}, timeout=20)
    response.raise_for_status()
    return response.json()["data"][0]["embedding"]


def _kb_query(session_id: str, query: str) -> str:
    """Build the retrieval query.

    An explicit query (typically the candidate's last answer or the topic the
    interviewer wants to probe) is used as-is, because padding it with the job
    title dilutes the vector. Only when nothing is supplied do we synthesise a
    query from the job and resume context.
    """
    query = (query or "").strip()
    if query:
        return query
    context = get_context(session_id)
    job = context.get("job") or {}
    resume = context.get("resume") or {}
    parts = [
        str(job.get("title") or ""),
        " ".join(_job_skills(session_id)),
        str(resume.get("targetRole") or ""),
    ]
    return " ".join(part for part in parts if part).strip() or "计算机岗位面试"


@tool
def search_real_interview_patterns(session_id: str, query: str = "", stage: str = "", limit: int = 4) -> str:
    """检索真实面试对话和公开面经，学习主问题、追问链和面试官提问方式。

    返回的是真实提问模式，不是标准答案。生成下一问时应吸收其追问逻辑，并结合当前简历和上一轮回答改写。
    """
    if not EXPERIENCE_PATTERNS:
        return json.dumps({"available": False, "reason": "experience-patterns-not-loaded", "items": []}, ensure_ascii=False)
    context = get_context(session_id)
    job = context.get("job") or {}
    context_text = " ".join([
        str(job.get("title") or ""),
        " ".join(_job_skills(session_id)),
        str(job.get("jobRequirements") or job.get("description") or ""),
        _resume_text(session_id),
        query or "",
    ])
    wanted_stage = (stage or "").strip()
    query_tokens = _retrieval_tokens(context_text)
    explicit_tokens = _retrieval_tokens(query)
    ranked = []
    for item in EXPERIENCE_PATTERNS:
        question_text = " ".join([
            str(item.get("question") or ""), " ".join(item.get("followUps") or []),
            item.get("section") or "", " ".join(item.get("tags") or []),
        ])
        haystack = " ".join([
            question_text, item.get("role") or "", item.get("stage") or "",
        ])
        item_tokens = _retrieval_tokens(haystack)
        overlap = len(query_tokens & item_tokens)
        explicit_overlap = len(explicit_tokens & item_tokens)
        score = overlap * 1.5 + explicit_overlap * 3.0
        if wanted_stage and wanted_stage == item.get("stage"):
            score += 8.0
        tags = [str(value).lower() for value in (item.get("tags") or [])]
        if any(tag and tag in context_text.lower() for tag in tags):
            score += 3.0
        if score > 0:
            ranked.append((score, item))
    ranked.sort(key=lambda pair: (-pair[0], pair[1].get("id", "")))
    size = max(1, min(int(limit or 4), 6))
    items = []
    for score, item in ranked[:size]:
        items.append({
            "score": round(score, 2),
            "id": item.get("id"),
            "stage": item.get("stage"),
            "role": item.get("role"),
            "companyType": item.get("companyType"),
            "section": item.get("section"),
            "question": item.get("question"),
            "followUps": item.get("followUps") or [],
            "interviewerMoves": item.get("interviewerMoves") or [],
            "sourceId": item.get("sourceId"),
            "sourceFile": item.get("sourceFile"),
            "sourceTitle": item.get("sourceTitle"),
            "sourceUrl": item.get("sourceUrl"),
            "level": item.get("level"),
            "outcome": item.get("outcome"),
            "usageNote": "借鉴提问方式和追问链，结合当前岗位与候选人回答改写；不得复制候选人答案。",
        })
    return json.dumps({
        "available": True,
        "count": len(items),
        "query": context_text[:500],
        "stageFilter": wanted_stage,
        "items": items,
        "attribution": "真实面试资料来自用户提供的公开视频整理稿和公开面经合集。",
    }, ensure_ascii=False)


@tool
def search_interview_questions_qdrant(session_id: str, query: str = "", stage: str = "", limit: int = 4) -> str:
    """从本地面试题向量库（Qdrant）检索真实面试题与参考答案，返回来源和许可证。

    与 search_question_bank 的小型内置模板不同，本工具检索的是已入库的公开题库向量，
    适合需要具体技术细节、原理或权威参考要点时使用。
    """
    text = _kb_query(session_id, query)
    size = max(1, min(int(limit or 4), 10))
    # Only filter by stage when the caller is explicit, or when there is no
    # topic at all (opening question). A wrong stage filter is worse than none.
    selected_stage = (stage or "").strip()
    if not selected_stage and not (query or "").strip():
        selected_stage = (_select_stage(session_id) or "").strip()
    try:
        vector = _kb_embed(text)
    except Exception as exc:  # noqa: BLE001 - planner falls back to the static bank
        return json.dumps(
            {"source": "qdrant", "available": False, "stage": "embedding-unavailable",
             "error": str(exc), "items": []}, ensure_ascii=False)

    def run(stage_value: str):
        body = {"vector": vector, "limit": size, "with_payload": True}
        if stage_value in KB_STAGES:
            body["filter"] = {"must": [{"key": "stage", "match": {"value": stage_value}}]}
        response = requests.post(
            INTERVIEW_QDRANT_URL + "/collections/" + INTERVIEW_COLLECTION + "/points/search",
            json=body,
            timeout=25,
        )
        response.raise_for_status()
        return response.json().get("result", [])

    try:
        hits = run(selected_stage)
        top = float(hits[0].get("score", 0.0)) if hits else 0.0
        if selected_stage and top < 0.35:
            # The stage filter is starving the result set: retry unfiltered.
            unfiltered = run("")
            if unfiltered and float(unfiltered[0].get("score", 0.0)) > top:
                hits = unfiltered
                selected_stage = ""
    except Exception as exc:  # noqa: BLE001
        return json.dumps(
            {"source": "qdrant", "available": False, "error": str(exc), "items": []},
            ensure_ascii=False,
        )
    items = []
    for hit in hits:
        payload = hit.get("payload") or {}
        items.append({
            "score": round(float(hit.get("score", 0.0)), 3),
            "question": payload.get("question"),
            "referenceAnswer": (payload.get("answer") or "")[:600],
            "stage": payload.get("stage"),
            "role": payload.get("role"),
            "skills": payload.get("skills"),
            "kind": payload.get("kind"),
            "source": payload.get("source"),
            "sourceUrl": payload.get("sourceUrl"),
            "license": payload.get("license"),
        })
    return json.dumps({
        "source": "qdrant:" + INTERVIEW_COLLECTION,
        "available": True,
        "query": text,
        "stageFilter": selected_stage,
        "count": len(items),
        "attribution": "题目原文来自公开 GitHub 仓库（MIT / Apache-2.0 / CC-BY-4.0 / CC-BY-SA-4.0），使用时请保留来源与许可证。",
        "items": items,
    }, ensure_ascii=False)


AGENT_TOOLS = [
    read_session_context,
    get_job_requirements,
    search_real_interview_patterns,
    search_question_bank,
    search_interview_questions_qdrant,
    evaluate_candidate_answer,
    save_interview_memory,
    get_interview_progress,
]